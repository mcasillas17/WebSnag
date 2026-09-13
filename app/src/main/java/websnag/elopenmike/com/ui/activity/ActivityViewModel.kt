package websnag.elopenmike.com.ui.activity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import websnag.elopenmike.com.core.activity.ActivityGranularity
import websnag.elopenmike.com.core.activity.focusIntervals
import websnag.elopenmike.com.core.activity.focusPerWindow
import websnag.elopenmike.com.core.activity.ongoingSessionStart
import websnag.elopenmike.com.core.activity.periodBoundaries
import websnag.elopenmike.com.core.model.FocusSessionRecord
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What the Activity screen shows: a calendar period of [granularity] and the day selected in it.
 * A null [selectedDate] follows today, so the current period rolls over at midnight.
 */
data class ActivitySelection(
    val granularity: ActivityGranularity = ActivityGranularity.WEEK,
    val selectedDate: LocalDate? = null
)

/** One chart bar: a day of a week or month, or a month of a year (then [date] is its first day). */
data class ActivityBar(
    val date: LocalDate,
    val focusMs: Long,
    /** Bar height relative to the period's largest bar; zero when there is no focus. */
    val heightFraction: Float,
    /** Axis text: a narrow day or month name, or in a month only days 1, 8, 15, 22 and 29 (blank otherwise). */
    val axisLabel: String,
    /** Spoken date and focus total for accessibility services. */
    val description: String,
    val isSelected: Boolean,
    val isCurrent: Boolean,
    val isFuture: Boolean
)

data class ActivityUiState(
    val granularity: ActivityGranularity = ActivityGranularity.WEEK,
    val periodLabel: String = "",
    val isCurrentPeriod: Boolean = true,
    val bars: List<ActivityBar> = emptyList(),
    /** The selected bar's date and total, such as "Wednesday, March 5 · 1h 30m". */
    val selectedBarSummary: String = "",
    val totalLabel: String = "",
    val totalFocusMs: Long = 0,
    val dailyAverageFocusMs: Long = 0,
    /** Days the average spans: the whole period, or only its elapsed days while it is current. */
    val averageDays: Int = 0,
    val currentStreakDays: Int = 0,
    /** Null in the year view, which drills down by month instead of by day. */
    val selectedDayLabel: String? = null,
    val selectedDayFocusMs: Long = 0,
    val selectedDaySessions: List<FocusSessionRecord> = emptyList(),
    val selectedDayHasOngoingSession: Boolean = false
)

class ActivityViewModel(
    focusSessions: Flow<List<FocusSessionRecord>>,
    activeSessionStart: Flow<Long?>,
    private val savedStateHandle: SavedStateHandle,
    private val clock: () -> Clock = Clock::systemDefaultZone,
    private val locale: () -> Locale = Locale::getDefault
) : ViewModel() {

    // Saved state is untrusted input: anything of the wrong type or outside 1970-01-01..yesterday is
    // ignored, so a stale or foreign value falls back to the current week instead of crashing.
    private val _selection = MutableStateFlow(
        ActivitySelection(
            granularity = (savedStateHandle.get<Any?>(KEY_GRANULARITY) as? String)
                ?.let { name -> ActivityGranularity.entries.firstOrNull { it.name == name } }
                ?: ActivityGranularity.WEEK,
            selectedDate = (savedStateHandle.get<Any?>(KEY_SELECTED_EPOCH_DAY) as? Long)
                ?.takeIf { it in 0 until LocalDate.now(clock()).toEpochDay() }
                ?.let(LocalDate::ofEpochDay)
        )
    )
    internal val selection: StateFlow<ActivitySelection> = _selection.asStateFlow()

    // Recomputes on each minute boundary while the screen is subscribed, so an ongoing session and
    // midnight rollover stay current. It stops with the subscription and never touches enforcement.
    private val minuteTicks = flow {
        while (true) {
            emit(Unit)
            delay(MINUTE_MS - clock().millis() % MINUTE_MS)
        }
    }

    val uiState: StateFlow<ActivityUiState> = combine(
        focusSessions,
        activeSessionStart,
        _selection,
        minuteTicks
    ) { sessions, activeStart, selection, _ ->
        build(sessions, activeStart, selection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), build(emptyList(), null, _selection.value))

    fun selectGranularity(granularity: ActivityGranularity) = update(_selection.value.copy(granularity = granularity))

    fun showCurrentPeriod() = update(_selection.value.copy(selectedDate = null))

    fun showPreviousPeriod() = shift(-1)

    fun showNextPeriod() = shift(1)

    /** Selects a day of a week or month; a month bar of a year opens that month. Future bars are ignored. */
    fun selectBar(date: LocalDate) {
        val today = LocalDate.now(clock())
        if (date > today) return
        val current = _selection.value
        update(
            if (current.granularity == ActivityGranularity.YEAR) {
                ActivitySelection(ActivityGranularity.MONTH, date.takeIf { YearMonth.from(it) != YearMonth.from(today) })
            } else {
                current.copy(selectedDate = date.takeIf { it < today })
            }
        )
    }

    private fun shift(step: Long) {
        val today = LocalDate.now(clock())
        val current = _selection.value
        val date = current.selectedDate ?: today
        val periodEnd = periodBoundaries(current.granularity, date, WeekFields.of(locale()).firstDayOfWeek).last()
        if (step > 0 && today < periodEnd) return
        val moved = when (current.granularity) {
            ActivityGranularity.WEEK -> date.plusWeeks(step)
            ActivityGranularity.MONTH -> date.plusMonths(step)
            ActivityGranularity.YEAR -> date.plusYears(step)
        }
        update(current.copy(selectedDate = moved.takeIf { it < today }))
    }

    private fun update(selection: ActivitySelection) {
        _selection.value = selection
        savedStateHandle[KEY_GRANULARITY] = selection.granularity.name
        savedStateHandle[KEY_SELECTED_EPOCH_DAY] = selection.selectedDate?.toEpochDay()
    }

    private fun build(sessions: List<FocusSessionRecord>, activeStart: Long?, selection: ActivitySelection): ActivityUiState {
        val now = clock()
        return buildActivityUiState(sessions, activeStart, selection, now.millis(), now.zone, locale())
    }

    private companion object {
        const val KEY_GRANULARITY = "activity_granularity"
        const val KEY_SELECTED_EPOCH_DAY = "activity_selected_epoch_day"
    }
}

/**
 * Builds the Activity screen for [selection] at [nowMs] in [zone]. Retained history is all there
 * is: dates without records show zero rather than being reconstructed or flagged as missing.
 */
internal fun buildActivityUiState(
    sessions: List<FocusSessionRecord>,
    activeSessionStartMs: Long?,
    selection: ActivitySelection,
    nowMs: Long,
    zone: ZoneId,
    locale: Locale
): ActivityUiState {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val selected = selection.selectedDate?.takeIf { it < today } ?: today
    val granularity = selection.granularity
    val boundaries = periodBoundaries(granularity, selected, WeekFields.of(locale).firstDayOfWeek)
    val intervals = focusIntervals(sessions, activeSessionStartMs, nowMs)
    val perBar = focusPerWindow(intervals, boundaries, zone)
    val maxMs = perBar.max()
    val isYear = granularity == ActivityGranularity.YEAR
    val selectedBar = if (isYear) selected.withDayOfMonth(1) else selected
    val currentBar = if (isYear) today.withDayOfMonth(1) else today
    val barName = if (isYear) monthYear(locale) else DateTimeFormatter.ofPattern("EEEE, MMMM d", locale)

    val bars = boundaries.dropLast(1).mapIndexed { index, date ->
        val isFuture = date > today
        val name = barName.format(date)
        ActivityBar(
            date = date,
            focusMs = perBar[index],
            heightFraction = if (maxMs == 0L) 0f else perBar[index].toFloat() / maxMs,
            // Single-letter day and month names fit their slots even at the largest text sizes;
            // the full date is in the description and the selected-bar summary.
            axisLabel = when (granularity) {
                ActivityGranularity.WEEK -> date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
                ActivityGranularity.MONTH -> if ((date.dayOfMonth - 1) % 7 == 0) date.dayOfMonth.toString() else ""
                ActivityGranularity.YEAR -> date.month.getDisplayName(TextStyle.NARROW, locale)
            },
            description = if (isFuture) "$name: upcoming" else "$name: ${spokenFocusDuration(perBar[index])} of focus",
            isSelected = date == selectedBar,
            isCurrent = date == currentBar,
            isFuture = isFuture
        )
    }

    val periodStart = boundaries.first()
    val periodEnd = boundaries.last()
    val averageDays = ChronoUnit.DAYS.between(periodStart, minOf(periodEnd, today.plusDays(1))).toInt().coerceAtLeast(1)
    val totalMs = perBar.sum()

    // Consecutive days with focus, ending today, or yesterday while today has none yet.
    val recentDays = focusPerWindow(intervals, (STREAK_DAYS downTo -1L).map { today.minusDays(it) }, zone).reversed()
    val streak = recentDays.drop(if (recentDays.first() > 0) 0 else 1).takeWhile { it > 0 }.size

    val ongoingStart = ongoingSessionStart(sessions, activeSessionStartMs)
    val dayStartMs = selected.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEndMs = selected.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    return ActivityUiState(
        granularity = granularity,
        periodLabel = when (granularity) {
            ActivityGranularity.WEEK -> {
                val last = periodEnd.minusDays(1)
                val longFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
                val startFormat = if (periodStart.year == last.year) DateTimeFormatter.ofPattern("MMM d", locale) else longFormat
                "${startFormat.format(periodStart)} – ${longFormat.format(last)}"
            }
            ActivityGranularity.MONTH -> monthYear(locale).format(periodStart)
            ActivityGranularity.YEAR -> periodStart.year.toString()
        },
        isCurrentPeriod = today < periodEnd,
        bars = bars,
        selectedBarSummary = bars.first { it.isSelected }.let {
            "${barName.format(it.date)} · ${formatFocusDuration(it.focusMs)}"
        },
        totalLabel = "${granularity.label} total",
        totalFocusMs = totalMs,
        dailyAverageFocusMs = totalMs / averageDays,
        averageDays = averageDays,
        currentStreakDays = streak,
        selectedDayLabel = when {
            isYear -> null
            selected == today -> "Today, " + DateTimeFormatter.ofPattern("MMM d", locale).format(selected)
            selected.year == today.year -> DateTimeFormatter.ofPattern("EEEE, MMM d", locale).format(selected)
            else -> DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", locale).format(selected)
        },
        selectedDayFocusMs = if (isYear) 0L else perBar[ChronoUnit.DAYS.between(periodStart, selected).toInt()],
        selectedDaySessions = if (isYear) emptyList() else sessions
            // Listed exactly when the record adds focus time to this day, matching the day's total.
            .filter { maxOf(it.startTimeEpochMs, dayStartMs) < minOf(it.endTimeEpochMs, nowMs, dayEndMs) }
            .sortedByDescending { it.startTimeEpochMs },
        selectedDayHasOngoingSession = !isYear && ongoingStart != null && ongoingStart < dayEndMs && nowMs > dayStartMs
    )
}

/** Display name of a granularity, such as "Week". */
val ActivityGranularity.label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

/**
 * Compact focus duration, such as "45m" or "1h 5m". Positive time under a minute reads "<1m", so a
 * bar that is drawn is never labelled as zero.
 */
fun formatFocusDuration(ms: Long): String {
    val minutes = ms / MINUTE_MS
    return when {
        ms in 1 until MINUTE_MS -> "<1m"
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

private fun spokenFocusDuration(ms: Long): String {
    fun count(value: Long, unit: String) = "$value $unit" + if (value == 1L) "" else "s"
    val hours = ms / MINUTE_MS / 60
    val rest = ms / MINUTE_MS % 60
    return when {
        ms in 1 until MINUTE_MS -> "less than a minute"
        hours == 0L -> count(rest, "minute")
        rest == 0L -> count(hours, "hour")
        else -> count(hours, "hour") + " " + count(rest, "minute")
    }
}

private fun monthYear(locale: Locale) = DateTimeFormatter.ofPattern("LLLL yyyy", locale)

private const val MINUTE_MS = 60_000L
private const val STREAK_DAYS = 30L
