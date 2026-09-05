package websnag.elopenmike.com.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.backup.BackupCodec
import websnag.elopenmike.com.core.backup.BackupRepository
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleRecord

@RunWith(AndroidJUnit4::class)
class ScheduleBackupConsistencyTest {
    private lateinit var harness: MigrationStoreHarness
    private val passphrase get() = "synthetic fixture passphrase only".toCharArray()
    @Before fun setup() { harness = MigrationStoreHarness() }
    @After fun cleanup() = runBlocking<Unit> { if (::harness.isInitialized) harness.close() }

    private suspend fun seedInactive() {
        harness.seed("alpha2-current")
        harness.local.saveProfiles(harness.local.profilesFlow.first().map { it.copy(isActive = false, activatedAtEpochMs = null) })
        harness.local.setActiveProfileId(null)
    }

    @Test fun deletingScheduledProfileLeavesAnExportableRestorableSnapshot() = runBlocking<Unit> {
        seedInactive()
        val profiles = DefaultProfileRepository(harness.local)
        val deleted = profiles.getProfiles().last()
        val schedule = ScheduleRecord("synthetic-linked-schedule", "Synthetic routine", deleted.id, deleted.name,
            startHour = 9, startMinute = 0, daysOfWeek = setOf(ScheduleDay.MON))
        harness.local.saveSchedule(schedule)
        val repository = BackupRepository(harness.local, profiles)
        BackupCodec.decrypt(repository.export(passphrase, true), passphrase)
        profiles.deleteProfile(deleted.id)
        assertFalse(harness.local.schedulesFlow.first().any { it.profileId == deleted.id })
        harness.open()
        val snapshot = harness.local.createBackupSnapshot(true)
        val envelope = BackupRepository(harness.local, DefaultProfileRepository(harness.local)).export(passphrase, true)
        harness.local.deleteAllUserData()
        assertEquals(BackupRepository.RestoreResult.Restored,
            BackupRepository(harness.local, DefaultProfileRepository(harness.local)).restore(envelope, passphrase))
        assertEquals(snapshot, harness.local.createBackupSnapshot(true))
    }

    @Test fun nonexistentProfileScheduleCannotCreateUnexportableState() = runBlocking<Unit> {
        seedInactive()
        val before = harness.raw()
        val schedule = ScheduleRecord("synthetic-orphan", "Synthetic routine", "synthetic-missing", "Synthetic missing",
            startHour = 9, startMinute = 0, daysOfWeek = setOf(ScheduleDay.MON))
        assertFalse(harness.local.saveSchedule(schedule))
        assertTrue("missing profile schedule must not change stored state", before == harness.raw())
        harness.open()
        val repository = BackupRepository(harness.local, DefaultProfileRepository(harness.local))
        BackupCodec.decrypt(repository.export(passphrase, true), passphrase)
    }
    @Test fun deletingActiveProfileOrMalformedRelatedStatePreservesAllBytes() = runBlocking<Unit> {
        for (marker in listOf("flag", "id", "malformed")) {
            seedInactive()
            val profile = harness.local.profilesFlow.first().first()
            if (marker == "flag") harness.local.saveProfiles(harness.local.profilesFlow.first().map {
                if (it.id == profile.id) it.copy(isActive = true) else it
            })
            if (marker == "id") harness.local.setActiveProfileId(profile.id)
            if (marker == "malformed") harness.store.updateData { it.toMutablePreferences().apply {
                this[androidx.datastore.preferences.core.stringPreferencesKey("schedules_json")] = "{synthetic malformed schedule"
            } }
            val before = harness.raw()
            assertTrue(runCatching { DefaultProfileRepository(harness.local).deleteProfile(profile.id) }.isFailure)
            assertTrue("refused deletion must preserve both collections and all state", before == harness.raw())
            harness.open()
            assertTrue(before == harness.raw())
        }
    }
    @Test fun implicitDefaultSchedulesCannotIntroduceDanglingReferencesDuringDeletion() = runBlocking<Unit> {
        seedInactive()
        harness.store.updateData { it.toMutablePreferences().apply {
            remove(androidx.datastore.preferences.core.stringPreferencesKey("schedules_json"))
        } }
        val repository = DefaultProfileRepository(harness.local)
        repository.deleteProfile(repository.getProfiles().last().id)
        val backup = BackupRepository(harness.local, repository)
        BackupCodec.decrypt(backup.export(passphrase, true), passphrase)
    }
    @Test fun toggleDoesNotPersistOrphanDefaultsFromAbsentOrMalformedSchedules() = runBlocking<Unit> {
        verifyDefaultMaterialization(toggle = true)
    }

    @Test fun deleteDoesNotPersistOrphanDefaultsFromAbsentOrMalformedSchedules() = runBlocking<Unit> {
        verifyDefaultMaterialization(toggle = false)
    }

    private suspend fun verifyDefaultMaterialization(toggle: Boolean) {
        val key = androidx.datastore.preferences.core.stringPreferencesKey("schedules_json")
        for (malformed in listOf(false, true)) {
            seedInactive()
            harness.store.updateData { it.toMutablePreferences().apply {
                if (malformed) this[key] = "{synthetic malformed schedule" else remove(key)
            } }
            // Reading still exposes the existing disabled defaults without repairing stored bytes.
            val fallback = harness.local.schedulesFlow.first().first()
            if (toggle) harness.local.toggleSchedule(fallback.id, true) else harness.local.deleteSchedule(fallback.id)
            harness.open()
            val snapshot = harness.local.createBackupSnapshot(true)
            val ids = snapshot.profiles.map { it.id }.toSet()
            assertTrue("fallback write cannot persist dangling schedules", snapshot.schedules.all { it.profileId in ids })
            val backup = BackupRepository(harness.local, DefaultProfileRepository(harness.local))
            assertEquals(snapshot, BackupCodec.decrypt(backup.export(passphrase, true), passphrase))
        }
    }
}
