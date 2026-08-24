package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleEndMode
import websnag.elopenmike.com.core.model.ScheduleRecord
import java.util.Calendar

class ScheduleManagerTest {

    @Test
    fun testFormattedTimeWindow() {
        val schedule = ScheduleRecord(
            id = "test-1",
            name = "Work Focus",
            profileId = "p1",
            profileName = "Deep Work",
            filterMode = FilterMode.ALLOWLIST,
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI),
            isEnabled = true
        )
        assertEquals("9:00 AM - 5:00 PM", schedule.formattedTimeWindow)
        assertEquals("Weekdays", schedule.daysSummary)
    }

    @Test
    fun testOnNfcTapEndMode() {
        val schedule = ScheduleRecord(
            id = "test-nfc",
            name = "Indefinite Work Focus",
            profileId = "p1",
            profileName = "Deep Work",
            filterMode = FilterMode.ALLOWLIST,
            startHour = 9,
            startMinute = 0,
            endMode = ScheduleEndMode.ON_NFC_TAP,
            daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI),
            isEnabled = true
        )

        assertEquals("9:00 AM • On NFC Tap", schedule.formattedTimeWindow)

        // Wednesday 10:30 AM (after 9:00 AM)
        val wednesdayMorning = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 30)
        }.timeInMillis

        assertTrue(schedule.isCurrentlyActive(wednesdayMorning))

        // Wednesday 8:30 AM (before 9:00 AM start)
        val wednesdayEarly = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 30)
        }.timeInMillis

        assertFalse(schedule.isCurrentlyActive(wednesdayEarly))
    }

    @Test
    fun testWifiConditionEvaluation() {
        val schedule = ScheduleRecord(
            id = "test-wifi",
            name = "Office Focus",
            profileId = "p1",
            profileName = "Deep Work",
            filterMode = FilterMode.ALLOWLIST,
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            requiresWifi = true,
            wifiSsid = "Office-5G",
            daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI),
            isEnabled = true
        )

        val wednesdayMorning = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 30)
        }.timeInMillis

        // When connected to Office-5G: active
        assertTrue(schedule.isCurrentlyActive(wednesdayMorning, currentConnectedSsid = "Office-5G"))

        // When connected to different WiFi: inactive
        assertFalse(schedule.isCurrentlyActive(wednesdayMorning, currentConnectedSsid = "Home-WiFi"))

        // When not connected to WiFi: inactive
        assertFalse(schedule.isCurrentlyActive(wednesdayMorning, currentConnectedSsid = null))
    }

    @Test
    fun testSameDayScheduleActiveWindow() {
        val schedule = ScheduleRecord(
            id = "test-1",
            name = "Work Focus",
            profileId = "p1",
            profileName = "Deep Work",
            filterMode = FilterMode.ALLOWLIST,
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI),
            isEnabled = true
        )

        // Wednesday 10:30 AM
        val wednesdayMorning = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 30)
        }.timeInMillis

        assertTrue(schedule.isCurrentlyActive(wednesdayMorning))

        // Wednesday 6:00 PM (after end time)
        val wednesdayEvening = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
        }.timeInMillis

        assertFalse(schedule.isCurrentlyActive(wednesdayEvening))

        // Saturday 10:30 AM (weekend not in daysOfWeek)
        val saturdayMorning = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 30)
        }.timeInMillis

        assertFalse(schedule.isCurrentlyActive(saturdayMorning))
    }

    @Test
    fun testOvernightScheduleActiveWindow() {
        val schedule = ScheduleRecord(
            id = "test-overnight",
            name = "Night Winddown",
            profileId = "p2",
            profileName = "Bedtime Rest",
            filterMode = FilterMode.BLOCKLIST,
            startHour = 22,
            startMinute = 30,
            endHour = 7,
            endMinute = 0,
            daysOfWeek = setOf(ScheduleDay.SUN, ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI, ScheduleDay.SAT),
            isEnabled = true
        )

        assertEquals("10:30 PM - 7:00 AM", schedule.formattedTimeWindow)
        assertEquals("Every day", schedule.daysSummary)

        // 11:15 PM (before midnight)
        val lateNight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 15)
        }.timeInMillis

        assertTrue(schedule.isCurrentlyActive(lateNight))

        // 6:15 AM (after midnight, before 7:00 AM)
        val earlyMorning = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 15)
        }.timeInMillis

        assertTrue(schedule.isCurrentlyActive(earlyMorning))

        // 2:00 PM (outside window)
        val afternoon = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
        }.timeInMillis

        assertFalse(schedule.isCurrentlyActive(afternoon))
    }

    @Test
    fun testDisabledScheduleNeverActive() {
        val schedule = ScheduleRecord(
            id = "test-disabled",
            name = "Disabled Routine",
            profileId = "p1",
            profileName = "Deep Work",
            filterMode = FilterMode.ALLOWLIST,
            startHour = 0,
            startMinute = 0,
            endHour = 23,
            endMinute = 59,
            daysOfWeek = ScheduleDay.values().toSet(),
            isEnabled = false
        )

        assertFalse(schedule.isCurrentlyActive(System.currentTimeMillis()))
    }

    @Test
    fun testOverlappingSchedulesDetection() {
        val schedule1 = ScheduleRecord(
            id = "s1",
            name = "Work schedule",
            profileId = "p1",
            profileName = "Work",
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI),
            isEnabled = true
        )

        // WFH on Mon, Wed 9:00 AM On NFC Tap overlaps with schedule1
        val schedule2 = ScheduleRecord(
            id = "s2",
            name = "WFH",
            profileId = "p1",
            profileName = "Work",
            startHour = 9,
            startMinute = 0,
            endMode = ScheduleEndMode.ON_NFC_TAP,
            daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.WED),
            isEnabled = true
        )

        assertTrue(schedule2.overlapsWith(schedule1))
        assertTrue(schedule1.overlapsWith(schedule2))

        // Weekend schedule should not overlap
        val weekendSchedule = ScheduleRecord(
            id = "s3",
            name = "Weekend",
            profileId = "p1",
            profileName = "Work",
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            daysOfWeek = setOf(ScheduleDay.SAT, ScheduleDay.SUN),
            isEnabled = true
        )

        assertFalse(schedule2.overlapsWith(weekendSchedule))
    }
}
