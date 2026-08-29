package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleRecord
import websnag.elopenmike.com.core.schedule.ScheduleOccurrence
import websnag.elopenmike.com.core.schedule.ScheduleReconciler
import java.util.Calendar

class ScheduleReconcilerTest {

    private val schedule = ScheduleRecord(
        id = "work",
        name = "Work",
        profileId = "profile",
        profileName = "Profile",
        filterMode = FilterMode.BLOCKLIST,
        startHour = 9,
        startMinute = 0,
        endHour = 17,
        endMinute = 0,
        daysOfWeek = ScheduleDay.values().toSet()
    )

    @Test
    fun `manual ending suppresses only the current schedule occurrence`() {
        val now = atHour(10)
        val occurrence = ScheduleReconciler.occurrenceFor(schedule, now)!!
        val dismissed = occurrence.copy(dismissed = true)

        assertFalse(ScheduleReconciler.shouldActivate(schedule, dismissed, now))
        assertTrue(ScheduleReconciler.shouldActivate(schedule, dismissed, atHour(10, dayOffset = 1)))
    }

    @Test
    fun `expired occurrence always requests a schedule end after delayed reconciliation`() {
        val occurrence = ScheduleOccurrence(
            scheduleId = "work",
            occurrenceStartEpochMs = atHour(9),
            occurrenceEndEpochMs = atHour(17)
        )

        assertEquals(
            ScheduleReconciler.Decision.END,
            ScheduleReconciler.reconcile(schedule, occurrence, atHour(18))
        )
    }

    private fun atHour(hour: Int, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, dayOffset)
    }.timeInMillis
}
