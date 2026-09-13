package websnag.elopenmike.com.core.activity

import websnag.elopenmike.com.core.model.FocusSessionRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Calendar period shown by an Activity chart: daily bars for a week or month, monthly bars for a year. */
enum class ActivityGranularity { WEEK, MONTH, YEAR }

/**
 * Local-date boundaries of the calendar period of [granularity] that contains [date]: each bar's
 * first day, followed by the period's exclusive end. A week has 7 bars, a month one per calendar
 * day and a year 12 monthly bars, whatever history exists.
 */
fun periodBoundaries(granularity: ActivityGranularity, date: LocalDate, firstDayOfWeek: DayOfWeek): List<LocalDate> =
    when (granularity) {
        ActivityGranularity.WEEK -> {
            val start = date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            (0L..7L).map(start::plusDays)
        }
        ActivityGranularity.MONTH -> {
            val start = date.withDayOfMonth(1)
            (0L..date.lengthOfMonth()).map(start::plusDays)
        }
        ActivityGranularity.YEAR -> {
            val start = date.withDayOfYear(1)
            (0L..12L).map(start::plusMonths)
        }
    }

/**
 * Recorded focus plus the ongoing session as `[start, end)` epoch-millisecond intervals, clipped to
 * [nowMs] so no future time is counted. The ongoing session is skipped once its completed record
 * (which keeps the same start) has arrived, so it is never counted twice. Reversed or empty records
 * contribute nothing; nothing here fabricates or alters history.
 */
fun focusIntervals(
    sessions: List<FocusSessionRecord>,
    activeSessionStartMs: Long?,
    nowMs: Long
): List<LongRange> {
    val recorded = sessions.map { it.startTimeEpochMs until minOf(it.endTimeEpochMs, nowMs) }
    val ongoing = ongoingSessionStart(sessions, activeSessionStartMs)?.let { it until nowMs }
    return (recorded + listOfNotNull(ongoing)).filterNot(LongRange::isEmpty)
}

/** The active session's start while its completed record has not been saved yet, otherwise null. */
fun ongoingSessionStart(sessions: List<FocusSessionRecord>, activeSessionStartMs: Long?): Long? =
    activeSessionStartMs?.takeIf { start -> sessions.none { it.startTimeEpochMs == start } }

/**
 * Focus milliseconds inside each local-day window `[boundaries[i], boundaries[i + 1])` in [zone].
 * Windows start at each date's actual local start of day, so daylight-saving days keep their real
 * length and sessions crossing midnight, a month or a year are split at the boundary.
 */
fun focusPerWindow(intervals: List<LongRange>, boundaries: List<LocalDate>, zone: ZoneId): List<Long> {
    val edges = boundaries.map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
    val first = edges.first()
    val end = edges.last()
    val relevant = intervals.filter { it.first < end && it.last + 1 > first }
    return edges.zipWithNext { windowStart, windowEnd ->
        relevant.sumOf { maxOf(0L, minOf(it.last + 1, windowEnd) - maxOf(it.first, windowStart)) }
    }
}
