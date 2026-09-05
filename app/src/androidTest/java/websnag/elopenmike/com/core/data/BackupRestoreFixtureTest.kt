package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.backup.*
import websnag.elopenmike.com.core.model.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class BackupRestoreFixtureTest {
    private lateinit var harness: MigrationStoreHarness
    private val passphrase get() = "synthetic fixture passphrase only".toCharArray()
    @Before fun setup() { harness = MigrationStoreHarness() }
    @After fun cleanup() = runBlocking { if (::harness.isInitialized) harness.close() }
    private fun repository() = BackupRepository(harness.local, DefaultProfileRepository(harness.local))

    @Test fun encryptedSnapshotRestoresAtomicallyWithoutImportingActiveProfile() = runBlocking {
        harness.seed("alpha2-current")
        val source = harness.local.createBackupSnapshot(true)
        val envelope = repository().export(passphrase, true)
        harness.local.deleteAllUserData()
        assertEquals(BackupRepository.RestoreResult.Restored, repository().restore(envelope, passphrase))
        harness.open()
        val restored = harness.local.createBackupSnapshot(true)
        assertEquals(source.copy(profiles = source.profiles.map { it.copy(isActive = false, activatedAtEpochMs = null) }), restored)
        assertNull(harness.local.activeProfileIdFlow.first())
        assertNull(DefaultProfileRepository(harness.local).activeProfileFlow.first())
        assertTrue(harness.local.nfcTagsFlow.first().all { it.customPayload == null })
        assertNull(harness.local.emergencyRecoveryFlow.first())
        assertNull(harness.local.activeScheduleOccurrenceFlow.first())
    }

    @Test fun omittedHistoryClearsExistingHistoryAndSeparateRecoveryStateIsNotImported() = runBlocking {
        harness.seed("alpha2-current")
        val snapshot = harness.local.createBackupSnapshot(false)
        assertTrue(snapshot.history.isEmpty())
        val envelope = BackupCodec.encrypt(snapshot, passphrase)
        harness.local.saveProfiles(emptyList())
        harness.local.setActiveProfileId(null)
        val recovery = harness.local.emergencyRecoveryFlow.first()
        val occurrence = harness.local.activeScheduleOccurrenceFlow.first()
        assertEquals(BackupRepository.RestoreResult.Restored, repository().restore(envelope, passphrase))
        harness.open()
        assertTrue(harness.local.focusSessionsFlow.first().isEmpty())
        // Existing contract: these destination-only keys are preserved, not imported from the backup.
        assertEquals(recovery, harness.local.emergencyRecoveryFlow.first())
        assertEquals(occurrence, harness.local.activeScheduleOccurrenceFlow.first())
    }

    @Test fun eitherActiveMarkerRefusesRestoreAndKeepsEveryPreference() = runBlocking {
        val envelope = BackupCodec.encrypt(BackupSnapshot(), passphrase)
        for (marker in listOf("both", "id-only", "flag-only")) {
            harness.seed("alpha2-current")
            if (marker == "id-only") harness.local.saveProfiles(harness.local.profilesFlow.first().map { it.copy(isActive = false) })
            if (marker == "flag-only") harness.local.setActiveProfileId(null)
            val before = harness.raw()
            assertEquals(marker, BackupRepository.RestoreResult.ActiveLockConflict, repository().restore(envelope, passphrase))
            assertTrue("active conflict must retain all destination preferences", before == harness.raw())
            harness.open()
            assertTrue("active refusal must also survive reload", before == harness.raw())
        }
    }

    @Test fun activeStateCreatedAfterPrecheckIsRejectedInsideTransaction() = runBlocking {
        harness.seed("alpha2-current")
        harness.local.saveProfiles(harness.local.profilesFlow.first().map { it.copy(isActive = false) })
        harness.local.setActiveProfileId(null)
        val before = harness.raw()
        val delegate = DefaultProfileRepository(harness.local)
        val racing = object : ProfileRepository by delegate {
            override val activeProfileFlow = flow<Profile?> {
                harness.local.setActiveProfileId("synthetic-concurrent-lock")
                emit(null)
            }
        }
        val repo = BackupRepository(harness.local, racing)
        val envelope = BackupCodec.encrypt(BackupSnapshot(), passphrase)
        assertEquals(BackupRepository.RestoreResult.ActiveLockConflict, repo.restore(envelope, passphrase))
        val expected = before.toMutablePreferences().apply {
            this[stringPreferencesKey("active_profile_id")] = "synthetic-concurrent-lock"
        }
        assertTrue("atomic guard must preserve the concurrent lock and all other state", expected == harness.raw())
    }

    @Test fun invalidEnvelopesNeverPartiallyWriteDestination() = runBlocking {
        harness.seed("alpha2-current")
        harness.local.saveProfiles(harness.local.profilesFlow.first().map { it.copy(isActive = false) })
        harness.local.setActiveProfileId(null)
        val before = harness.raw()
        val valid = BackupCodec.encrypt(BackupSnapshot(), passphrase)
        val bad = mutableListOf(
            valid.copyOf(3), valid.copyOf(20), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            ByteArray(1_048_577), valid.copyOf().apply { this[4] = 127 },
            valid.copyOf().apply { this[9] = 0 }, valid.copyOf().apply { this[10] = 0 },
            valid.copyOf().apply { ByteBuffer.wrap(this).putInt(5, 1) },
            valid.copyOf().apply { ByteBuffer.wrap(this).putInt(39, -1) })
        listOf(11, 27, valid.lastIndex).forEach { offset ->
            bad += valid.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
        }
        val p = "{\"id\":\"synthetic-duplicate\",\"name\":\"Synthetic\"}"
        val schedule = "{\"id\":\"synthetic-schedule\",\"name\":\"Synthetic\",\"profileId\":\"synthetic-profile\",\"profileName\":\"Synthetic\",\"startHour\":24,\"startMinute\":0,\"daysOfWeek\":[\"MON\"]}"
        listOf(
            "{\"profiles\":[$p,$p]}",
            "{\"profiles\":[" + List(101) { "{\"id\":\"synthetic-$it\",\"name\":\"Synthetic\"}" }.joinToString() + "]}",
            "{\"schedules\":[$schedule]}", "{\"historyRetentionDays\":0}",
            "{\"futureSyntheticField\":true}", "{synthetic invalid json",
            "{\"profiles\":[{\"id\":\"synthetic\",\"name\":\"Synthetic\",\"description\":\"" + "x".repeat(786_432) + "\"}]}"
        ).forEach { bad += withAuthenticatedPayload(it) }
        assertTrue(runCatching { repository().restore(valid, "synthetic wrong passphrase".toCharArray()) }.exceptionOrNull() is BackupException.AuthenticationFailed)
        assertTrue(before == harness.raw())
        bad.forEachIndexed { index, envelope ->
            assertTrue("invalid envelope case $index must be rejected", runCatching { repository().restore(envelope, passphrase) }.exceptionOrNull() is BackupException)
            assertTrue("invalid envelope case $index must not partially write", before == harness.raw())
        }
        harness.open()
        assertTrue("all rejected restores leave the same persisted bytes", before == harness.raw())
    }

    @Test fun authenticatedAmbiguousTagsAndInvalidScheduleReferencesNeverWrite() = runBlocking {
        harness.seed("alpha2-current")
        harness.local.saveProfiles(harness.local.profilesFlow.first().map { it.copy(isActive = false) })
        harness.local.setActiveProfileId(null)
        val before = harness.raw()
        val valid = harness.local.createBackupSnapshot(true)
        // Positive control: the adversarial encoder must itself produce a valid production envelope.
        val json = Json { encodeDefaults = true }
        assertEquals(valid, BackupCodec.decrypt(withAuthenticatedPayload(json.encodeToString(valid)), passphrase))
        val tag = valid.tags.first()
        val schedule = valid.schedules.first()
        val invalid = listOf(
            valid.copy(tags = listOf(tag, tag.copy(uidFingerprint = "SYNTHETIC_OTHER_FINGERPRINT"))),
            valid.copy(tags = listOf(tag, tag.copy(id = "synthetic-other-id"))),
            valid.copy(schedules = listOf(schedule.copy(daysOfWeek = emptySet()))),
            valid.copy(schedules = listOf(schedule.copy(profileId = "synthetic-missing"))),
            valid.copy(schedules = listOf(schedule.copy(profileId = ""))),
            valid.copy(schedules = listOf(schedule.copy(profileName = "")))
        )
        invalid.forEachIndexed { index, snapshot ->
            val envelope = withAuthenticatedPayload(json.encodeToString(snapshot))
            assertTrue("authenticated invalid snapshot $index must fail validation",
                runCatching { repository().restore(envelope, passphrase) }.exceptionOrNull() is BackupException.InvalidInput)
            assertTrue("authenticated invalid snapshot $index must preserve every preference", before == harness.raw())
            harness.open()
            assertTrue("rejection must survive reload", before == harness.raw())
        }
    }

    /**
     * Start with a production-generated WSB1 header/salt/KDF. Replace its payload using a fresh
     * random nonce to model an authenticated but invalid external writer. Production encrypt
     * deliberately cannot create invalid snapshots; this adversarial writer exists only in tests.
     */
    private fun withAuthenticatedPayload(payload: String): ByteArray {
        val template = BackupCodec.encrypt(BackupSnapshot(), passphrase)
        val iterations = ByteBuffer.wrap(template).getInt(5)
        val salt = template.copyOfRange(11, 27)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        try {
            val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                doFinal(payload.toByteArray())
            }
            return ByteBuffer.allocate(43 + encrypted.size).put(template.copyOfRange(0, 27))
                .put(nonce).putInt(encrypted.size).put(encrypted).array()
        } finally { key.fill(0) }
    }
}
