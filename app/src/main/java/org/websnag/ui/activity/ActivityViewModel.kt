package org.websnag.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.websnag.core.data.LocalDataStore
import org.websnag.core.enforcement.EnforcementEngine
import org.websnag.core.model.FocusSessionRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DayFocusStat(
    val dayLabel: String,
    val dateLabel: String,
    val focusMinutes: Int,
    val isToday: Boolean
)

data class ActivityUiState(
    val todayFocusMinutes: Int = 0,
    val todaySessionsCount: Int = 0,
    val todayDistractionsBlocked: Int = 0,
    val allTimeFocusMinutes: Int = 0,
    val currentStreakDays: Int = 0,
    val weeklyStats: List<DayFocusStat> = emptyList(),
    val recentSessions: List<FocusSessionRecord> = emptyList()
)

class ActivityViewModel(
    private val localDataStore: LocalDataStore,
    private val enforcementEngine: EnforcementEngine
) : ViewModel() {

    val uiState: StateFlow<ActivityUiState> = combine(
        localDataStore.focusSessionsFlow,
        enforcementEngine.enforcementState
    ) { sessions, enforcementState ->
        computeActivityStats(sessions, enforcementState.sessionStartedAtEpochMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActivityUiState())

    private fun computeActivityStats(
        sessions: List<FocusSessionRecord>,
        activeSessionStartEpochMs: Long?
    ): ActivityUiState {
        val calendar = Calendar.getInstance()
        val todayYear = calendar.get(Calendar.YEAR)
        val todayDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

        var todaySeconds = 0L
        var todaySessions = 0
        var todayBlocked = 0
        var allTimeSeconds = 0L

        sessions.forEach { session ->
            allTimeSeconds += session.durationSeconds
            calendar.timeInMillis = session.startTimeEpochMs
            if (calendar.get(Calendar.YEAR) == todayYear && calendar.get(Calendar.DAY_OF_YEAR) == todayDayOfYear) {
                todaySeconds += session.durationSeconds
                todaySessions++
                todayBlocked += session.interceptionsPrevented
            }
        }

        // Add currently active session if running
        if (activeSessionStartEpochMs != null) {
            val activeSec = maxOf(0L, (System.currentTimeMillis() - activeSessionStartEpochMs) / 1000L)
            todaySeconds += activeSec
            allTimeSeconds += activeSec
        }

        // Compute 7-day weekly stats
        val weeklyList = mutableListOf<DayFocusStat>()
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val dateFormat = SimpleDateFormat("d", Locale.getDefault())

        for (i in 6 downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val y = cal.get(Calendar.YEAR)
            val d = cal.get(Calendar.DAY_OF_YEAR)
            val isToday = (i == 0)

            var daySec = 0L
            sessions.forEach { s ->
                val sCal = Calendar.getInstance().apply { timeInMillis = s.startTimeEpochMs }
                if (sCal.get(Calendar.YEAR) == y && sCal.get(Calendar.DAY_OF_YEAR) == d) {
                    daySec += s.durationSeconds
                }
            }
            if (isToday && activeSessionStartEpochMs != null) {
                daySec += maxOf(0L, (System.currentTimeMillis() - activeSessionStartEpochMs) / 1000L)
            }

            weeklyList.add(
                DayFocusStat(
                    dayLabel = if (isToday) "Today" else dayFormat.format(cal.time),
                    dateLabel = dateFormat.format(cal.time),
                    focusMinutes = (daySec / 60).toInt(),
                    isToday = isToday
                )
            )
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
                // today might not have a session yet, check yesterday
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }

        return ActivityUiState(
            todayFocusMinutes = (todaySeconds / 60).toInt(),
            todaySessionsCount = todaySessions + (if (activeSessionStartEpochMs != null) 1 else 0),
            todayDistractionsBlocked = todayBlocked,
            allTimeFocusMinutes = (allTimeSeconds / 60).toInt(),
            currentStreakDays = maxOf(if (todaySessions > 0 || activeSessionStartEpochMs != null) 1 else 0, streak),
            weeklyStats = weeklyList,
            recentSessions = sessions.take(20)
        )
    }
}
