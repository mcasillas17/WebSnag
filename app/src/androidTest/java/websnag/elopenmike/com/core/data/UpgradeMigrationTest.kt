package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.UnlockPolicy
import websnag.elopenmike.com.core.model.AppThemeMode
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.core.nfc.NfcActionResolver
import websnag.elopenmike.com.core.nfc.NfcTagAction
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpgradeMigrationTest {
    private lateinit var harness: MigrationStoreHarness
    private val alias = "synthetic.websnag.migration.${UUID.randomUUID()}"
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val protector get() = AndroidKeystoreTagIdentityProtector(alias)

    @Before fun setup() { harness = MigrationStoreHarness() }
    @After fun cleanup() = runBlocking {
        try { if (::harness.isInitialized) harness.close() } finally { keyStore.deleteEntry(alias) }
    }

    @Test fun persistedStartupMigratesBeforeRepositoryConsumersAndReloadKeepsState() = runBlocking {
        harness.seed("mixed")
        val original = harness.raw()
        harness.open(webSnagPreferenceMigrations(protector))
        val local = harness.local
        val profiles = DefaultProfileRepository(local)
        profiles.initializeDefaultProfilesIfNeeded()
        val active = profiles.activeProfileFlow.first()
        assertNotNull(active)
        assertEquals("synthetic-profile-active", active!!.id)
        assertEquals(1700000100000L, active.activatedAtEpochMs)
        val tags = local.nfcTagsFlow.first()
        val expectedTags = ExpectedMigrationState.tags(
            protector.fingerprint("A0B1C2D3")!!, protector.fingerprint("D4E5F607")!!, mixed = true
        )
        assertEquals(expectedTags, tags)
        assertEquals(ExpectedMigrationState.profiles(mixed = true), local.profilesFlow.first())
        assertEquals(3, tags.size)
        assertEquals(1700000000000L, tags[0].createdAtEpochMs)
        assertEquals(1700000060000L, tags[0].lastUsedEpochMs)
        assertEquals("Synthetic \"desk\"\\tag\n雪", tags[0].label)
        val condition = active.unlockCondition as UnlockCondition.RequireNfcTag
        assertEquals("synthetic-tag-b", condition.requiredTagId)
        assertEquals(17, condition.emergencyCooldownMinutes)
        assertTrue(condition.requireIntentionPhrase)
        assertFalse(UnlockPolicy.canEnd(condition, EndRequest.Manual))
        assertFalse(UnlockPolicy.canEnd(condition, EndRequest.Emergency(false, true)))
        assertFalse(UnlockPolicy.canEnd(condition, EndRequest.Emergency(true, false)))
        val migrated = harness.raw()
        for (key in listOf("emergency_recovery_json", "active_schedule_occurrence_json", "focus_sessions_json", "schedules_json")) {
            assertTrue("unrelated persisted state must survive: $key", original[stringPreferencesKey(key)] == migrated[stringPreferencesKey(key)])
        }
        assertEquals(AppThemeMode.DARK, local.themeModeFlow.first())
        assertEquals(3650, local.historyRetentionDaysFlow.first())
        val recovery = local.emergencyRecoveryFlow.first()!!
        assertEquals(1020000L, recovery.durationMs)
        assertFalse(recovery.intentionConfirmed)
        assertTrue(local.activeScheduleOccurrenceFlow.first()!!.dismissed)
        val snapshot = local.createBackupSnapshot(true)
        val postMigrationText = migrated.asMap().values.joinToString()
        assertFalse("synthetic raw inputs must not survive migration", listOf("A0B1C2D3", "D4E5F607", "uidHex", "requiredTagUid", "linkedTagUid").any(postMigrationText::contains))
        harness.open(webSnagPreferenceMigrations(protector))
        assertTrue("reload must not rewrite migrated preferences", migrated == harness.raw())
        assertEquals(snapshot, harness.local.createBackupSnapshot(true))
        assertNotNull(DefaultProfileRepository(harness.local).activeProfileFlow.first())
        assertEquals(expectedTags, harness.local.nfcTagsFlow.first())
        assertEquals(ExpectedMigrationState.profiles(mixed = true), harness.local.profilesFlow.first())
        val resolver = NfcActionResolver(DefaultProfileRepository(harness.local), DefaultNfcTagRepository(harness.local, protector))
        assertTrue(resolver.resolve("A0B1C2D3") is NfcTagAction.UnlockRejected)
        assertTrue(resolver.resolve("D4E5F607") is NfcTagAction.DeactivateProfile)
        assertTrue(resolver.resolve("00112233") is NfcTagAction.UnlockRejected)
    }

    @Test fun keyLossDoesNotTurnPersistedFingerprintsIntoPortableCredentials() = runBlocking {
        harness.seed("alpha1")
        harness.open(webSnagPreferenceMigrations(protector))
        val before = harness.local.nfcTagsFlow.first()
        assertTrue(keyStore.containsAlias(alias))
        keyStore.deleteEntry(alias)
        harness.open(webSnagPreferenceMigrations(protector))
        val resolver = NfcActionResolver(DefaultProfileRepository(harness.local), DefaultNfcTagRepository(harness.local, protector))
        assertTrue(resolver.resolve("D4E5F607") is NfcTagAction.UnlockRejected)
        assertEquals(before, harness.local.nfcTagsFlow.first())
        assertNotNull(DefaultProfileRepository(harness.local).activeProfileFlow.first())
    }
    @Test fun ambiguousCurrentIdentitiesStayOnDiskButCannotAuthorizeAfterReload() = runBlocking {
        harness.seed("alpha1")
        harness.open(webSnagPreferenceMigrations(protector))
        val valid = harness.local.nfcTagsFlow.first()
        for (tags in listOf(listOf(valid[1], valid[0].copy(id = valid[1].id)),
            listOf(valid[1], valid[1].copy(id = "synthetic-other-id")))) {
            harness.store.updateData { it.toMutablePreferences().apply {
                this[stringPreferencesKey("nfc_tags_json")] = Json.encodeToString(tags)
            } }
            val before = harness.raw()
            harness.open(webSnagPreferenceMigrations(protector))
            val resolver = NfcActionResolver(DefaultProfileRepository(harness.local), DefaultNfcTagRepository(harness.local, protector))
            assertTrue(resolver.resolve("D4E5F607") is NfcTagAction.UnlockRejected)
            assertTrue(resolver.resolve("A0B1C2D3") is NfcTagAction.UnlockRejected)
            assertTrue("ambiguous current identities must remain available for recovery", before == harness.raw())
            assertNotNull(DefaultProfileRepository(harness.local).activeProfileFlow.first())
        }
    }
}
