package websnag.elopenmike.com.core.activity

import org.junit.Assert.assertEquals
import org.junit.Test
import websnag.elopenmike.com.core.model.FocusSessionRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FocusPeriodsTest {
    private val zone = ZoneId.of("America/New_York")
    private val hour = 3_600_000L
    private val minute = 60_000L

    private fun ms(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: String, end: String, id: String = start) = FocusSessionRecord(
        id = id,
        profileId = "p",
        profileName = "Synthetic",
        startTimeEpochMs = ms(start),
        endTimeEpochMs = ms(end),
        durationSeconds = (ms(end) - ms(start)) / 1000
    )

    private fun buckets(granularity: ActivityGranularity, date: String, weekStart: DayOfWeek = DayOfWeek.MONDAY) =
        periodBoundaries(granularity, LocalDate.parse(date), weekStart)

    private fun allocate(
        granularity: ActivityGranularity,
        date: String,
        sessions: List<FocusSessionRecord>,
        now: String = "2030-01-01T00:00",
        activeStart: Long? = null,
        weekStart: DayOfWeek = DayOfWeek.MONDAY
    ) = focusPerWindow(
        focusIntervals(sessions, activeStart, ms(now)),
        buckets(granularity, date, weekStart),
        zone
    )

    @Test fun weekHasSevenDailyBucketsStartingOnTheLocaleFirstDay() {
        val monday = buckets(ActivityGranularity.WEEK, "2025-03-05")
        assertEquals(8, monday.size)
        assertEquals(LocalDate.parse("2025-03-03"), monday.first())
        assertEquals(LocalDate.parse("2025-03-10"), monday.last())

        val sunday = buckets(ActivityGranularity.WEEK, "2025-03-05", DayOfWeek.SUNDAY)
        assertEquals(LocalDate.parse("2025-03-02"), sunday.first())
        assertEquals(LocalDate.parse("2025-03-09"), sunday.last())
    }

    @Test fun weekCrossingAYearBoundaryStaysOneCalendarWeek() {
        val week = buckets(ActivityGranularity.WEEK, "2026-01-01")
        assertEquals(LocalDate.parse("2025-12-29"), week.first())
        assertEquals(LocalDate.parse("2026-01-05"), week.last())
    }

    @Test fun monthHasOneBucketPerCalendarDay() {
        assertEquals(28, buckets(ActivityGranularity.MONTH, "2025-02-14").size - 1)
        assertEquals(29, buckets(ActivityGranularity.MONTH, "2024-02-29").size - 1)
        assertEquals(30, buckets(ActivityGranularity.MONTH, "2025-04-30").size - 1)
        assertEquals(31, buckets(ActivityGranularity.MONTH, "2025-12-01").size - 1)
        assertEquals(LocalDate.parse("2026-01-01"), buckets(ActivityGranularity.MONTH, "2025-12-31").last())
    }

    @Test fun yearHasTwelveMonthlyBuckets() {
        val year = buckets(ActivityGranularity.YEAR, "2024-07-04")
        assertEquals(13, year.size)
        assertEquals(LocalDate.parse("2024-01-01"), year.first())
        assertEquals(LocalDate.parse("2024-12-01"), year[11])
        assertEquals(LocalDate.parse("2025-01-01"), year.last())
    }

    @Test fun emptyHistoryAndMissingDatesAreZero() {
        assertEquals(List(7) { 0L }, allocate(ActivityGranularity.WEEK, "2025-03-05", emptyList()))
        val month = allocate(ActivityGranularity.MONTH, "2025-03-05", listOf(session("2025-03-10T09:00", "2025-03-10T10:00")))
        assertEquals(31, month.size)
        assertEquals(hour, month[9])
        assertEquals(hour, month.sum())
    }

    @Test fun multipleSessionsAddToTheirOwnDay() {
        val week = allocate(
            ActivityGranularity.WEEK, "2025-03-05",
            listOf(
                session("2025-03-03T09:00", "2025-03-03T09:30", "a"),
                session("2025-03-03T13:00", "2025-03-03T13:45", "b"),
                session("2025-03-07T08:00", "2025-03-07T10:00", "c"),
                session("2025-02-20T08:00", "2025-02-20T10:00", "outside")
            )
        )
        assertEquals(listOf(75 * minute, 0, 0, 0, 2 * hour, 0, 0), week)
    }

    @Test fun overnightSessionIsSplitAtLocalMidnight() {
        val week = allocate(ActivityGranularity.WEEK, "2025-03-05", listOf(session("2025-03-04T23:00", "2025-03-05T01:30")))
        assertEquals(listOf(0, hour, 90 * minute, 0, 0, 0, 0), week)
    }

    @Test fun crossMonthSessionIsSplitBetweenMonths() {
        val record = listOf(session("2025-03-31T22:00", "2025-04-01T02:00"))
        assertEquals(2 * hour, allocate(ActivityGranularity.MONTH, "2025-03-10", record).last())
        assertEquals(2 * hour, allocate(ActivityGranularity.MONTH, "2025-04-10", record).first())
        val year = allocate(ActivityGranularity.YEAR, "2025-01-01", record)
        assertEquals(2 * hour, year[2])
        assertEquals(2 * hour, year[3])
    }

    @Test fun crossYearSessionIsSplitBetweenYears() {
        val record = listOf(session("2024-12-31T23:00", "2025-01-01T00:30"))
        assertEquals(hour, allocate(ActivityGranularity.YEAR, "2024-06-01", record)[11])
        assertEquals(30 * minute, allocate(ActivityGranularity.YEAR, "2025-06-01", record)[0])
        // The calendar week holding New Year keeps both parts.
        assertEquals(90 * minute, allocate(ActivityGranularity.WEEK, "2025-01-01", record).sum())
    }

    @Test fun leapDayIsItsOwnBucket() {
        val month = allocate(ActivityGranularity.MONTH, "2024-02-01", listOf(session("2024-02-29T10:00", "2024-02-29T11:00")))
        assertEquals(29, month.size)
        assertEquals(hour, month[28])
    }

    @Test fun daylightSavingDaysUseTheirActualLength() {
        // 2025-03-09 has 23 hours and 2025-11-02 has 25 hours in New York.
        val spring = allocate(ActivityGranularity.WEEK, "2025-03-09", listOf(session("2025-03-09T00:00", "2025-03-10T00:00")))
        assertEquals(23 * hour, spring.sum())
        assertEquals(23 * hour, spring[6])
        val fall = allocate(ActivityGranularity.WEEK, "2025-11-02", listOf(session("2025-11-02T00:00", "2025-11-03T00:00")))
        assertEquals(25 * hour, fall[6])
    }

    @Test fun ongoingSessionCountsUntilNow() {
        val week = allocate(ActivityGranularity.WEEK, "2025-03-05", emptyList(), now = "2025-03-05T00:30", activeStart = ms("2025-03-04T23:45"))
        assertEquals(listOf(0, 15 * minute, 30 * minute, 0, 0, 0, 0), week)
    }

    @Test fun completedRecordReplacesTheOngoingSessionInsteadOfAddingToIt() {
        val start = ms("2025-03-05T09:00")
        val ongoing = allocate(ActivityGranularity.WEEK, "2025-03-05", emptyList(), now = "2025-03-05T10:00", activeStart = start)
        val completedWhileStateStillActive = allocate(
            ActivityGranularity.WEEK, "2025-03-05", listOf(session("2025-03-05T09:00", "2025-03-05T10:00")),
            now = "2025-03-05T10:00", activeStart = start
        )
        assertEquals(hour, ongoing.sum())
        assertEquals(ongoing, completedWhileStateStillActive)
    }

    @Test fun futureBucketsStayZero() {
        val week = allocate(
            ActivityGranularity.WEEK, "2025-03-05", listOf(session("2025-03-05T09:00", "2025-03-07T09:00")),
            now = "2025-03-05T12:00"
        )
        assertEquals(listOf(0, 0, 3 * hour, 0, 0, 0, 0), week)
    }

    @Test fun malformedRecordsContributeNothing() {
        val reversed = session("2025-03-05T10:00", "2025-03-05T09:00")
        assertEquals(0L, allocate(ActivityGranularity.WEEK, "2025-03-05", listOf(reversed)).sum())
    }
}
