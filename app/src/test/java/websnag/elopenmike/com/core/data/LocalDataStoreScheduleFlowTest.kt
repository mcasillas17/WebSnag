package websnag.elopenmike.com.core.data

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleRecord

/**
 * Regression coverage for the schedule-reconciliation write loop: [LocalDataStore.schedulesFlow]
 * is built on top of the same single Preferences DataStore that
 * [websnag.elopenmike.com.core.schedule.ScheduleManager.evaluateCurrentSchedules] writes
 * `scheduleReconciliation` diagnostics into on every reconcile pass. Every underlying DataStore
 * write re-emits the whole `Preferences` snapshot to every collector of `context.dataStore.data`
 * -- including the `schedules_json` value even when it hasn't changed -- so
 * [mapRawScheduleJsonToDistinctSchedules], the exact transformation `schedulesFlow` is built
 * from, must dedupe on the *decoded* schedule list rather than passing every raw emission
 * through, or a metadata-only write (e.g. a reconciliation timestamp) re-triggers every
 * `schedulesFlow` collector -- including [websnag.elopenmike.com.core.schedule.ScheduleManager],
 * which reconciles and rewrites the same metadata again, forever.
 */
class LocalDataStoreScheduleFlowTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val schedule = ScheduleRecord(
        id = "sched-1",
        name = "Deep Work",
        profileId = "profile-1",
        profileName = "Deep Work",
        filterMode = FilterMode.ALLOWLIST,
        startHour = 9,
        startMinute = 0,
        endHour = 17,
        endMinute = 0,
        daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI),
        isEnabled = true
    )

    @Test
    fun `schedulesFlow transformation emits schedules once when only unrelated metadata changes`() = runBlocking {
        val scheduleJson = json.encodeToString(listOf(schedule))

        // Simulates three consecutive DataStore Preferences emissions where the persisted
        // `schedules_json` value never changes, because only an unrelated diagnostics field
        // (scheduleReconciliation, stored under a different key in the same Preferences
        // DataStore) is rewritten each time -- exactly what
        // ScheduleManager.evaluateCurrentSchedules does on every reconcile pass. The projected
        // `schedules_json` value seen by `schedulesFlow`'s source is identical across all three.
        val rawScheduleJsonEmissions = flow {
            emit(scheduleJson)
            emit(scheduleJson)
            emit(scheduleJson)
        }

        val emittedSchedules = rawScheduleJsonEmissions
            .mapRawScheduleJsonToDistinctSchedules(json) { emptyList() }
            .toList()

        assertEquals(
            "unrelated metadata-only writes must not cause repeat schedule emissions",
            1,
            emittedSchedules.size
        )
        assertEquals(listOf(schedule), emittedSchedules.single())
    }

    @Test
    fun `schedulesFlow transformation still emits when the decoded schedules actually change`() = runBlocking {
        val firstScheduleJson = json.encodeToString(listOf(schedule))
        val secondSchedule = schedule.copy(name = "Deep Work Renamed")
        val secondScheduleJson = json.encodeToString(listOf(secondSchedule))

        val rawScheduleJsonEmissions = flow {
            emit(firstScheduleJson)
            emit(firstScheduleJson)
            emit(secondScheduleJson)
        }

        val emittedSchedules = rawScheduleJsonEmissions
            .mapRawScheduleJsonToDistinctSchedules(json) { emptyList() }
            .toList()

        assertEquals(2, emittedSchedules.size)
        assertEquals(listOf(schedule), emittedSchedules[0])
        assertEquals(listOf(secondSchedule), emittedSchedules[1])
    }

    @Test
    fun `schedulesFlow transformation falls back to default schedules for malformed json`() = runBlocking {
        val fallback = listOf(schedule)
        val rawScheduleJsonEmissions = flow {
            emit("not valid json")
        }

        val emittedSchedules = rawScheduleJsonEmissions
            .mapRawScheduleJsonToDistinctSchedules(json) { fallback }
            .toList()

        assertEquals(listOf(fallback), emittedSchedules)
    }
}
