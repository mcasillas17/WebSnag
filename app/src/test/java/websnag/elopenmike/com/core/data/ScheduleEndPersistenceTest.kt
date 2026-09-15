package websnag.elopenmike.com.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleRecord
import websnag.elopenmike.com.core.schedule.ScheduleManager
import websnag.elopenmike.com.core.schedule.ScheduleOccurrence
import java.io.IOException
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleEndPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Harness(val test: TestScope) {
        val now = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 14, 11, 0, 0)
        }.timeInMillis
        val activeId = stringPreferencesKey("active_profile_id")
        val backing = PreferenceDataStoreFactory.create(scope = test.backgroundScope) {
            temporary.newFolder().resolve("schedule-end.preferences_pb")
        }
        var failNextEnd = false
        var failedEnds = 0
        var replaceOccurrenceAfterEnd: ScheduleOccurrence? = null
        val store: DataStore<Preferences> = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                var ended = false
                val committed = backing.updateData { before ->
                    val after = transform(before)
                    ended = before[activeId] != null && after[activeId] == null
                    if (failNextEnd && ended) {
                        failNextEnd = false
                        failedEnds++
                        throw IOException("synthetic one-shot end write failure")
                    }
                    after
                }
                if (ended) {
                    replaceOccurrenceAfterEnd?.also { replaceOccurrenceAfterEnd = null }?.let {
                        local.saveActiveScheduleOccurrence(it)
                    }
                }
                return committed
            }
        }
        val local: LocalDataStore = LocalDataStore(store)
        val profiles = DefaultProfileRepository(local)
        val engine = EnforcementEngine(profiles, local, test.backgroundScope, hasEnrolledNfcTag = { true })
        val manager = ScheduleManager(local, profiles, engine, test.backgroundScope)
        val occurrence = ScheduleOccurrence("old-schedule", now - 2 * 60 * 60_000L,
            now - 60 * 60_000L, profileId = "scheduled")

        suspend fun setup(path: String) {
            profiles.saveProfile(Profile("scheduled", "Scheduled"))
            profiles.saveProfile(Profile("other", "Other"))
            assertTrue(engine.tryActivateProfile("scheduled"))
            local.saveActiveScheduleOccurrence(occurrence)
            val schedule = when (path) {
                "replacement" -> ScheduleRecord("replacement", "Replacement", "other", "Other",
                    startHour = 10, startMinute = 0, endHour = 12, endMinute = 0,
                    daysOfWeek = ScheduleDay.entries.toSet())
                else -> ScheduleRecord("old-schedule", "Old", "scheduled", "Scheduled",
                    startHour = 9, startMinute = 0, endHour = if (path == "disabled") 17 else 10,
                    endMinute = 0, daysOfWeek = ScheduleDay.entries.toSet(), isEnabled = path != "disabled")
            }
            assertTrue(local.saveSchedule(schedule))
            test.runCurrent()
        }

        suspend fun assertFailedEndIsRetried(path: String) {
            setup(path)
            val session = profiles.readEnforcementSnapshot().activeProfile!!.sessionId
            failNextEnd = true
            manager.evaluateCurrentSchedules(now)
            test.runCurrent()
            assertEquals(1, failedEnds)
            assertEquals(session, profiles.readEnforcementSnapshot().activeProfile!!.sessionId)
            assertEquals("failed end must retain its occurrence for a later reconciliation",
                occurrence, local.activeScheduleOccurrenceFlow.first())
            assertEquals(ReconciliationOutcome.KEPT_ACTIVE, local.scheduleReconciliationFlow.first()!!.outcome)
            manager.evaluateCurrentSchedules(now)
            test.runCurrent()
            assertNull(profiles.readEnforcementSnapshot().activeProfile)
            assertNull(local.activeScheduleOccurrenceFlow.first())
            assertEquals(ReconciliationOutcome.ENDED, local.scheduleReconciliationFlow.first()!!.outcome)
            assertEquals(1, failedEnds)
            if (path == "replacement") {
                manager.evaluateCurrentSchedules(now)
                test.runCurrent()
                assertEquals("other", profiles.readEnforcementSnapshot().activeProfile!!.id)
                assertEquals("replacement", local.activeScheduleOccurrenceFlow.first()!!.scheduleId)
            }
        }
    }

    @Test fun expiredOccurrenceSurvivesFailedEndAndRetries() = runTest {
        Harness(this).assertFailedEndIsRetried("expired")
    }

    @Test fun disabledOccurrenceSurvivesFailedEndAndRetries() = runTest {
        Harness(this).assertFailedEndIsRetried("disabled")
    }

    @Test fun replacementOccurrenceSurvivesFailedEndAndRetries() = runTest {
        Harness(this).assertFailedEndIsRetried("replacement")
    }

    @Test fun alreadyEndedAndDifferentProfilesOnlyCleanUpTheOldOccurrence() = runTest {
        for (replacement in listOf<String?>(null, "other")) {
            val h = Harness(this)
            h.setup("expired")
            if (replacement == null) {
                assertTrue(h.engine.requestEnd("scheduled", EndRequest.ScheduleEnded))
            } else {
                assertTrue(h.engine.tryActivateProfile(replacement))
            }
            runCurrent()
            val before = h.profiles.readEnforcementSnapshot()
            h.manager.evaluateCurrentSchedules(h.now)
            runCurrent()
            assertEquals(before, h.profiles.readEnforcementSnapshot())
            assertNull(h.local.activeScheduleOccurrenceFlow.first())
            assertEquals(ReconciliationOutcome.NO_CHANGE, h.local.scheduleReconciliationFlow.first()!!.outcome)
        }
    }

    @Test fun dismissedOldOccurrenceCannotEndAReactivatedSameProfile() = runTest {
        val h = Harness(this)
        h.setup("expired")
        val oldSession = h.profiles.readEnforcementSnapshot().activeProfile!!.sessionId
        assertTrue(h.engine.requestEnd("scheduled", EndRequest.ScheduleEnded))
        h.local.saveActiveScheduleOccurrence(h.occurrence.copy(dismissed = true, endReason = "NFC"))
        assertTrue(h.engine.tryActivateProfile("scheduled"))
        runCurrent()
        val current = h.profiles.readEnforcementSnapshot().activeProfile!!
        assertNotEquals(oldSession, current.sessionId)
        h.manager.evaluateCurrentSchedules(h.now)
        runCurrent()
        assertEquals(current, h.profiles.readEnforcementSnapshot().activeProfile)
        assertNull(h.local.activeScheduleOccurrenceFlow.first())
        assertEquals(ReconciliationOutcome.NO_CHANGE, h.local.scheduleReconciliationFlow.first()!!.outcome)
    }

    @Test fun completedEndDoesNotClearAConcurrentlyReplacedOccurrence() = runTest {
        for (path in listOf("expired", "replacement")) {
            val h = Harness(this)
            h.setup(path)
            val replacement = h.occurrence.copy(scheduleId = "newer", profileId = "other")
            h.replaceOccurrenceAfterEnd = replacement
            h.manager.evaluateCurrentSchedules(h.now)
            runCurrent()
            assertNull(h.profiles.readEnforcementSnapshot().activeProfile)
            assertEquals(replacement, h.local.activeScheduleOccurrenceFlow.first())
            assertEquals(ReconciliationOutcome.ENDED, h.local.scheduleReconciliationFlow.first()!!.outcome)
        }
    }
}
