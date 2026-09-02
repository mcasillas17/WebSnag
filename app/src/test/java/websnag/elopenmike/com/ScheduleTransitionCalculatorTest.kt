package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleEndMode
import websnag.elopenmike.com.core.model.ScheduleRecord
import websnag.elopenmike.com.core.schedule.ScheduleTransitionCalculator
import java.util.Calendar

/**
 * Pure JVM tests for [ScheduleTransitionCalculator]. These exercise the exact same 9-day
 * lookahead/overnight-rollover algorithm [websnag.elopenmike.com.core.schedule.ScheduleAlarmCoordinator]
 * used to compute inline, now extracted so it can run without any Android framework dependency.
 */
class ScheduleTransitionCalculatorTest {

    private fun scheduleAt(
        startHour: Int,
        startMinute: Int,
        endHour: Int = 17,
        endMinute: Int = 0,
        endMode: ScheduleEndMode = ScheduleEndMode.AT_TIME,
        daysOfWeek: Set<ScheduleDay> = ScheduleDay.values().toSet(),
        isEnabled: Boolean = true
    ): ScheduleRecord = ScheduleRecord(
        id = "sched",
        name = "Test",
        profileId = "p1",
        profileName = "Profile",
        filterMode = FilterMode.BLOCKLIST,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        endMode = endMode,
        daysOfWeek = daysOfWeek,
        isEnabled = isEnabled
    )

    private fun at(hour: Int, minute: Int = 0, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, dayOffset)
    }.timeInMillis

    @Test
    fun `enabled schedule returns next start transition when before start time`() {
        val schedule = scheduleAt(startHour = 9, startMinute = 0, endHour = 17, endMinute = 0)
        val now = at(hour = 7)

        val next = ScheduleTransitionCalculator.nextTransitionEpochMs(listOf(schedule), now)

        assertEquals(at(hour = 9), next)
    }

    @Test
    fun `enabled schedule returns end transition when after start but before end`() {
        val schedule = scheduleAt(startHour = 9, startMinute = 0, endHour = 17, endMinute = 0)
        val now = at(hour = 10)

        val next = ScheduleTransitionCalculator.nextTransitionEpochMs(listOf(schedule), now)

        assertEquals(at(hour = 17), next)
    }

    @Test
    fun `disabled schedule contributes no transitions`() {
        val schedule = scheduleAt(startHour = 9, startMinute = 0, isEnabled = false)
        val now = at(hour = 0)

        assertNull(ScheduleTransitionCalculator.nextTransitionEpochMs(listOf(schedule), now))
    }

    @Test
    fun `ON_NFC_TAP schedule contributes only a start transition, never an end`() {
        val schedule = scheduleAt(startHour = 9, startMinute = 0, endMode = ScheduleEndMode.ON_NFC_TAP)
        val now = at(hour = 10) // already after today's start

        val next = ScheduleTransitionCalculator.nextTransitionEpochMs(listOf(schedule), now)

        // Must be tomorrow's start, never an "end" transition for an ON_NFC_TAP schedule.
        assertEquals(at(hour = 9, dayOffset = 1), next)
    }

    @Test
    fun `no enabled schedule yields no transition`() {
        val disabled = scheduleAt(startHour = 9, startMinute = 0, isEnabled = false)

        assertNull(ScheduleTransitionCalculator.nextTransitionEpochMs(emptyList(), at(hour = 12)))
        assertNull(ScheduleTransitionCalculator.nextTransitionEpochMs(listOf(disabled), at(hour = 12)))
    }

    @Test
    fun `overnight schedule end transition rolls into the following calendar day`() {
        val schedule = scheduleAt(startHour = 22, startMinute = 30, endHour = 7, endMinute = 0)
        val now = at(hour = 23)

        val next = ScheduleTransitionCalculator.nextTransitionEpochMs(listOf(schedule), now)

        assertEquals(at(hour = 7, dayOffset = 1), next)
    }

    @Test
    fun `returned transition is strictly greater than now, even exactly at a boundary`() {
        val schedule = scheduleAt(startHour = 9, startMinute = 0, endHour = 17, endMinute = 0)
        val now = at(hour = 9) // exactly at today's start time

        val next = ScheduleTransitionCalculator.nextTransitionEpochMs(listOf(schedule), now)

        assertTrue(next != null && next > now)
        assertEquals(at(hour = 17), next)
    }

    @Test
    fun `earliest transition across multiple enabled schedules wins`() {
        val earlier = scheduleAt(startHour = 8, startMinute = 0, endHour = 12, endMinute = 0)
        val later = scheduleAt(startHour = 13, startMinute = 0, endHour = 20, endMinute = 0)
        val now = at(hour = 0)

        val next = ScheduleTransitionCalculator.nextTransitionEpochMs(listOf(earlier, later), now)

        assertEquals(at(hour = 8), next)
    }
}
