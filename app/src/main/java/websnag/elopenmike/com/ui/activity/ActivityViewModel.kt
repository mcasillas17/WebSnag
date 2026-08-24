package websnag.elopenmike.com.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.FocusSessionRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CalendarDayTile(
    val epochStartOfDayMs: Long,
    val monthDayLabel: String, // e.g. "AUG 23"
    val dayOfWeekLabel: String, // e.g. "Fri"
    val focusMinutes: Int,
    val formattedTime: String, // e.g. "0h 14m"
    val sessionsCount: Int,
    val isSelected: Boolean,
    val isToday: Boolean
)

data class DayDistributionStat(
    val dayLabel: String, // "Mon", "Tue" ... "Today"
    val dateLabel: String, // "23"
    val focusMinutes: Int,
    val formattedTime: String, // "14m" or "1h 10m"
    val isToday: Boolean,
    val isSelected: Boolean,
    val epochStartOfDayMs: Long
)

data class ActivityUiState(
    val todayFocusMinutes: Int = 0,
    val averageDailyFocusMinutes: Int = 0,
    val todaySessionsCount: Int = 0,
    val todayDistractionsBlocked: Int = 0,
    val currentStreakDays: Int = 0,
    val selectedDateEpochMs: Long = System.currentTimeMillis(),
    val selectedDayLabel: String = "Today",
    val selectedDayFocusMinutes: Int = 0,
    val selectedDaySessions: List<FocusSessionRecord> = emptyList(),
    val calendarDays: List<CalendarDayTile> = emptyList(),
    val weeklyStats: List<DayDistributionStat> = emptyList()
)

class ActivityViewModel(
    private val localDataStore: LocalDataStore,
    private val enforcementEngine: EnforcementEngine
) : ViewModel() {

    private val _selectedDateEpochMs = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<ActivityUiState> = combine(
        localDataStore.focusSessionsFlow,
        enforcementEngine.enforcementState,
        _selectedDateEpochMs
    ) { sessions, enforcementState, customSelectedDateMs ->
        computeActivityStats(sessions, enforcementState.sessionStartedAtEpochMs, customSelectedDateMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActivityUiState())

    fun selectDate(epochMs: Long) {
        _selectedDateEpochMs.value = getStartOfDay(epochMs)
    }

    private fun getStartOfDay(epochMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun computeActivityStats(
        sessions: List<FocusSessionRecord>,
        activeSessionStartEpochMs: Long?,
        customSelectedDateMs: Long?
    ): ActivityUiState {
        val now = System.currentTimeMillis()
        val todayStartMs = getStartOfDay(now)
        val selectedDateMs = customSelectedDateMs ?: todayStartMs

        val monthDayFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val monthDayUpperFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
        val dayOfWeekFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val fullDateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

        val calendar = Calendar.getInstance()
        val todayYear = calendar.get(Calendar.YEAR)
        val todayDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

        var todaySeconds = 0L
        var todaySessions = 0
        var todayBlocked = 0

        sessions.forEach { session ->
            calendar.timeInMillis = session.startTimeEpochMs
            if (calendar.get(Calendar.YEAR) == todayYear && calendar.get(Calendar.DAY_OF_YEAR) == todayDayOfYear) {
                todaySeconds += session.durationSeconds
                todaySessions++
                todayBlocked += session.interceptionsPrevented
            }
        }

        // Add currently active session to today
        if (activeSessionStartEpochMs != null) {
            val activeSec = maxOf(0L, (now - activeSessionStartEpochMs) / 1000L)
            todaySeconds += activeSec
        }

        val todayMins = (todaySeconds / 60).toInt()

        // 7-day distribution list
        val weeklyList = mutableListOf<DayDistributionStat>()
        var total7DaySeconds = 0L

        for (i in 6 downTo 0) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = todayStartMs
                add(Calendar.DAY_OF_YEAR, -i)
            }
            val dayStartMs = cal.timeInMillis
            val y = cal.get(Calendar.YEAR)
            val d = cal.get(Calendar.DAY_OF_YEAR)
            val isToday = (i == 0)
            val isSelected = (dayStartMs == selectedDateMs)

            var daySec = 0L
            sessions.forEach { s ->
                val sCal = Calendar.getInstance().apply { timeInMillis = s.startTimeEpochMs }
                if (sCal.get(Calendar.YEAR) == y && sCal.get(Calendar.DAY_OF_YEAR) == d) {
                    daySec += s.durationSeconds
                }
            }
            if (isToday && activeSessionStartEpochMs != null) {
                daySec += maxOf(0L, (now - activeSessionStartEpochMs) / 1000L)
            }

            total7DaySeconds += daySec
            val mins = (daySec / 60).toInt()
            val hours = mins / 60
            val remMins = mins % 60
            val formatted = if (hours > 0) "${hours}h ${remMins}m" else "${remMins}m"

            weeklyList.add(
                DayDistributionStat(
                    dayLabel = if (isToday) "Today" else dayOfWeekFormat.format(cal.time),
                    dateLabel = cal.get(Calendar.DAY_OF_MONTH).toString(),
                    focusMinutes = mins,
                    formattedTime = formatted,
                    isToday = isToday,
                    isSelected = isSelected,
                    epochStartOfDayMs = dayStartMs
                )
            )
        }

        // 14-day Calendar Day Tiles Strip (Brick style)
        val calendarTiles = mutableListOf<CalendarDayTile>()
        for (i in 0..13) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = todayStartMs
                add(Calendar.DAY_OF_YEAR, -i)
            }
            val dayStartMs = cal.timeInMillis
            val y = cal.get(Calendar.YEAR)
            val d = cal.get(Calendar.DAY_OF_YEAR)
            val isToday = (i == 0)
            val isSelected = (dayStartMs == selectedDateMs)

            var daySec = 0L
            var dayCount = 0
            sessions.forEach { s ->
                val sCal = Calendar.getInstance().apply { timeInMillis = s.startTimeEpochMs }
                if (sCal.get(Calendar.YEAR) == y && sCal.get(Calendar.DAY_OF_YEAR) == d) {
                    daySec += s.durationSeconds
                    dayCount++
                }
            }
            if (isToday && activeSessionStartEpochMs != null) {
                daySec += maxOf(0L, (now - activeSessionStartEpochMs) / 1000L)
                dayCount++
            }

            val mins = (daySec / 60).toInt()
            val hours = mins / 60
            val remMins = mins % 60
            val formatted = "${hours}h ${remMins}m"

            calendarTiles.add(
                CalendarDayTile(
                    epochStartOfDayMs = dayStartMs,
                    monthDayLabel = monthDayUpperFormat.format(cal.time).uppercase(),
                    dayOfWeekLabel = dayOfWeekFormat.format(cal.time),
                    focusMinutes = mins,
                    formattedTime = formatted,
                    sessionsCount = dayCount,
                    isSelected = isSelected,
                    isToday = isToday
                )
            )
        }

        // Average daily focus across last 7 days (or active days)
        val averageDailyMins = (total7DaySeconds / 7 / 60).toInt()

        // Filter sessions for selected date
        val selectedCal = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
        val selYear = selectedCal.get(Calendar.YEAR)
        val selDayOfYear = selectedCal.get(Calendar.DAY_OF_YEAR)

        val selectedDaySessions = sessions.filter { s ->
            val sCal = Calendar.getInstance().apply { timeInMillis = s.startTimeEpochMs }
            sCal.get(Calendar.YEAR) == selYear && sCal.get(Calendar.DAY_OF_YEAR) == selDayOfYear
        }

        var selectedDaySec = selectedDaySessions.sumOf { it.durationSeconds }
        if (selectedDateMs == todayStartMs && activeSessionStartEpochMs != null) {
            selectedDaySec += maxOf(0L, (now - activeSessionStartEpochMs) / 1000L)
        }

        val selectedDayLabel = if (selectedDateMs == todayStartMs) {
            "Today (${monthDayFormat.format(selectedCal.time)})"
        } else {
            fullDateFormat.format(selectedCal.time)
        }

        // Calculate simple streak
        var streak = 0
        val checkCal = Calendar.getInstance()
        for (i in 0..30) {
            val y = checkCal.get(Calendar.YEAR)
            val d = checkCal.get(Calendar.DAY_OF_YEAR)
            val hadSession = sessions.any { s ->
                val sCal = Calendar.getInstance().apply { timeInMillis = s.startTimeEpochMs }
                sCal.get(Calendar.YEAR) == y && sCal.get(Calendar.DAY_OF_YEAR) == d
            } || (i == 0 && activeSessionStartEpochMs != null)

            if (hadSession) {
                streak++
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
            } else if (i == 0) {
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }

        return ActivityUiState(
            todayFocusMinutes = todayMins,
            averageDailyFocusMinutes = averageDailyMins,
            todaySessionsCount = todaySessions + (if (activeSessionStartEpochMs != null) 1 else 0),
            todayDistractionsBlocked = todayBlocked,
            currentStreakDays = maxOf(if (todaySessions > 0 || activeSessionStartEpochMs != null) 1 else 0, streak),
            selectedDateEpochMs = selectedDateMs,
            selectedDayLabel = selectedDayLabel,
            selectedDayFocusMinutes = (selectedDaySec / 60).toInt(),
            selectedDaySessions = selectedDaySessions,
            calendarDays = calendarTiles,
            weeklyStats = weeklyList
        )
    }
}
