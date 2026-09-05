package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.model.*
import websnag.elopenmike.com.core.schedule.ScheduleReconciler
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class PersistedStateFixtureTest {
    private lateinit var harness: MigrationStoreHarness
    @Before fun setup() { harness = MigrationStoreHarness() }
    @After fun cleanup() = runBlocking { if (::harness.isInitialized) harness.close() }

    @Test fun dismissedOccurrenceAndRecoverySurviveTheirProductionSaveMethodsAndReload() = runBlocking {
        harness.seed("alpha2-current")
        val local = harness.local
        val occurrence = local.activeScheduleOccurrenceFlow.first()!!
        val recovery = local.emergencyRecoveryFlow.first()!!
        local.saveActiveScheduleOccurrence(occurrence)
        local.saveEmergencyRecovery(recovery)
        harness.open()
        assertEquals(occurrence, harness.local.activeScheduleOccurrenceFlow.first())
        assertEquals(recovery, harness.local.emergencyRecoveryFlow.first())
        val oldZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val schedule = harness.local.schedulesFlow.first().single().copy(isEnabled = true)
            val inside = occurrence.occurrenceStartEpochMs + 60_000
            assertTrue("control schedule must be in its active window", schedule.isCurrentlyActive(inside))
            assertTrue(ScheduleReconciler.shouldActivate(schedule, null, inside))
            assertFalse(ScheduleReconciler.shouldActivate(schedule, harness.local.activeScheduleOccurrenceFlow.first(), inside))
        } finally { TimeZone.setDefault(oldZone) }
    }

    @Test fun malformedBytesStayStoredWhileFlowsEmitTheirExistingFallbacks() = runBlocking {
        harness.seed("malformed")
        val before = harness.raw()
        val local = harness.local
        assertTrue(local.profilesFlow.first().isEmpty())
        assertTrue(local.nfcTagsFlow.first().isEmpty())
        assertTrue(local.focusSessionsFlow.first().isEmpty())
        assertEquals(2, local.schedulesFlow.first().size)
        assertTrue(local.schedulesFlow.first().none { it.isEnabled })
        assertNull(local.emergencyRecoveryFlow.first())
        assertNull(local.activeScheduleOccurrenceFlow.first())
        assertEquals(AppThemeMode.SYSTEM, local.themeModeFlow.first())
        assertEquals(-9, local.historyRetentionDaysFlow.first())
        assertEquals("synthetic-profile-active", local.activeProfileIdFlow.first())
        assertNull(DefaultProfileRepository(local).activeProfileFlow.first())
        val backup = local.createBackupSnapshot(true)
        assertTrue(backup.profiles.isEmpty())
        assertTrue(backup.schedules.isEmpty()) // Backup decode differs from schedulesFlow defaults.
        assertTrue("fallback reads must not be confused with a repaired file", before == harness.raw())
        harness.open()
        assertTrue("reload retains malformed bytes", before == harness.raw())
        // DATA-001 evidence: a later ordinary write replaces the malformed source.
        harness.local.saveProfiles(listOf(Profile("synthetic-replacement", "Synthetic replacement")))
        assertFalse(before[stringPreferencesKey("profiles_json")] == harness.raw()[stringPreferencesKey("profiles_json")])
        assertTrue(before[stringPreferencesKey("nfc_tags_json")] == harness.raw()[stringPreferencesKey("nfc_tags_json")])
    }

    @Test fun absentValuesUseExistingDefaultsWithoutPersistingThem() = runBlocking {
        harness.open()
        val before = harness.raw()
        assertTrue(before.asMap().isEmpty())
        assertTrue(harness.local.profilesFlow.first().isEmpty())
        assertTrue(harness.local.nfcTagsFlow.first().isEmpty())
        assertEquals(2, harness.local.schedulesFlow.first().size)
        assertEquals(90, harness.local.historyRetentionDaysFlow.first())
        assertEquals(AppThemeMode.SYSTEM, harness.local.themeModeFlow.first())
        assertTrue(before == harness.raw())
    }

    @Test fun maximumHistoryIsRetainedOnLoadAndPrunedBySave() = runBlocking {
        harness.seed("alpha2-current")
        val now = 1700000000000L
        fun record(id: String, end: Long) = FocusSessionRecord(id, "synthetic-profile", "Synthetic history",
            startTimeEpochMs = end - 60_000, endTimeEpochMs = end, durationSeconds = 60)
        val original = List(500) { record("synthetic-history-$it", now - it * 1000) }
        harness.store.updateData { it.toMutablePreferences().apply {
            this[stringPreferencesKey("focus_sessions_json")] = Json.encodeToString(original)
            this[intPreferencesKey("history_retention_days")] = 1
        } }
        harness.open()
        assertEquals(original, harness.local.focusSessionsFlow.first())
        val newest = record("synthetic-newest", now + 60_000)
        LocalDataStore(harness.store) { now }.saveFocusSession(newest)
        val retained = harness.local.focusSessionsFlow.first()
        assertEquals(500, retained.size)
        assertEquals(newest, retained.first())
        assertEquals(original.take(499), retained.drop(1))
        val expired = record("synthetic-expired", now - 2 * 86_400_000L)
        LocalDataStore(harness.store) { now }.saveFocusSession(expired)
        assertEquals(retained, harness.local.focusSessionsFlow.first())
        harness.open()
        assertEquals(retained, harness.local.focusSessionsFlow.first())
    }
    @Test fun retentionCutoffIsInclusiveAndSettingBoundsDoNotWriteOnFailure() = runBlocking {
        harness.open()
        val now = 1700000000000L
        val local = LocalDataStore(harness.store) { now }
        local.setHistoryRetentionDays(1)
        val boundary = now - 86_400_000L
        fun record(id: String, end: Long) = FocusSessionRecord(id, "synthetic-profile", "Synthetic boundary",
            startTimeEpochMs = end - 1000, endTimeEpochMs = end, durationSeconds = 1)
        val atCutoff = record("synthetic-at-cutoff", boundary)
        val inside = record("synthetic-inside", boundary + 1)
        local.saveFocusSession(atCutoff)
        local.saveFocusSession(record("synthetic-outside", boundary - 1))
        local.saveFocusSession(inside)
        assertEquals(listOf(inside, atCutoff), local.focusSessionsFlow.first())
        val before = harness.raw()
        for (days in listOf(0, 3651)) {
            assertTrue(runCatching { local.setHistoryRetentionDays(days) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue("invalid retention must not write", before == harness.raw())
        }
        local.setHistoryRetentionDays(3650)
        harness.open()
        assertEquals(3650, harness.local.historyRetentionDaysFlow.first())
        assertEquals(listOf(inside, atCutoff), harness.local.focusSessionsFlow.first())
    }

}
