package org.websnag.core.model

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.Locale

@Serializable
enum class ScheduleDay {
    MON, TUE, WED, THU, FRI, SAT, SUN;

    val shortName: String
        get() = when (this) {
            MON -> "M"
            TUE -> "T"
            WED -> "W"
            THU -> "T"
            FRI -> "F"
            SAT -> "S"
            SUN -> "S"
        }

    val displayName: String
        get() = when (this) {
            MON -> "Mon"
            TUE -> "Tue"
            WED -> "Wed"
            THU -> "Thu"
            FRI -> "Fri"
            SAT -> "Sat"
            SUN -> "Sun"
        }

    companion object {
        fun fromCalendarDay(calendarDayOfWeek: Int): ScheduleDay {
            return when (calendarDayOfWeek) {
                Calendar.MONDAY -> MON
                Calendar.TUESDAY -> TUE
                Calendar.WEDNESDAY -> WED
                Calendar.THURSDAY -> THU
                Calendar.FRIDAY -> FRI
                Calendar.SATURDAY -> SAT
                Calendar.SUNDAY -> SUN
                else -> MON
            }
        }
    }
}

@Serializable
data class ScheduleRecord(
    val id: String,
    val name: String,
    val profileId: String,
    val profileName: String,
    val filterMode: FilterMode = FilterMode.BLOCKLIST,
    val startHour: Int, // 0..23
    val startMinute: Int, // 0..59
    val endHour: Int, // 0..23
    val endMinute: Int, // 0..59
    val daysOfWeek: Set<ScheduleDay>,
    val isEnabled: Boolean = true
) {
    val formattedTimeWindow: String
        get() {
            fun formatTime(hour: Int, min: Int): String {
                val amPm = if (hour < 12) "AM" else "PM"
                val h12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                return String.format(Locale.getDefault(), "%d:%02d %s", h12, min, amPm)
            }
            return "${formatTime(startHour, startMinute)} - ${formatTime(endHour, endMinute)}"
        }

    val daysSummary: String
        get() {
            if (daysOfWeek.size == 7) return "Every day"
            if (daysOfWeek == setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI)) return "Weekdays"
            if (daysOfWeek == setOf(ScheduleDay.SAT, ScheduleDay.SUN)) return "Weekends"
            return daysOfWeek.sortedBy { it.ordinal }.joinToString(", ") { it.displayName }
        }

    fun isCurrentlyActive(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (!isEnabled) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowEpochMs }
        val currentDay = ScheduleDay.fromCalendarDay(cal.get(Calendar.DAY_OF_WEEK))
        val currentMinuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val startMinuteOfDay = startHour * 60 + startMinute
        val endMinuteOfDay = endHour * 60 + endMinute

        return if (startMinuteOfDay <= endMinuteOfDay) {
            // Same day schedule (e.g. 9:00 AM to 5:00 PM)
            currentDay in daysOfWeek && currentMinuteOfDay in startMinuteOfDay until endMinuteOfDay
        } else {
            // Overnight schedule (e.g. 10:30 PM to 7:00 AM)
            if (currentMinuteOfDay >= startMinuteOfDay) {
                // First leg before midnight
                currentDay in daysOfWeek
            } else if (currentMinuteOfDay < endMinuteOfDay) {
                // Second leg after midnight (belongs to previous day's schedule)
                val yesterday = when (currentDay) {
                    ScheduleDay.MON -> ScheduleDay.SUN
                    ScheduleDay.TUE -> ScheduleDay.MON
                    ScheduleDay.WED -> ScheduleDay.TUE
                    ScheduleDay.THU -> ScheduleDay.WED
                    ScheduleDay.FRI -> ScheduleDay.THU
                    ScheduleDay.SAT -> ScheduleDay.FRI
                    ScheduleDay.SUN -> ScheduleDay.SAT
                }
                yesterday in daysOfWeek
            } else {
                false
            }
        }
    }
}
