package org.websnag.core.model

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.Locale

@Serializable
enum class ScheduleEndMode {
    AT_TIME,
    ON_NFC_TAP
}

@Serializable
enum class ScheduleDay {
    SUN, MON, TUE, WED, THU, FRI, SAT;

    val shortName: String
        get() = when (this) {
            SUN -> "S"
            MON -> "M"
            TUE -> "T"
            WED -> "W"
            THU -> "T"
            FRI -> "F"
            SAT -> "S"
        }

    val displayName: String
        get() = when (this) {
            SUN -> "Sun"
            MON -> "Mon"
            TUE -> "Tue"
            WED -> "Wed"
            THU -> "Thu"
            FRI -> "Fri"
            SAT -> "Sat"
        }

    companion object {
        fun fromCalendarDay(calendarDayOfWeek: Int): ScheduleDay {
            return when (calendarDayOfWeek) {
                Calendar.SUNDAY -> SUN
                Calendar.MONDAY -> MON
                Calendar.TUESDAY -> TUE
                Calendar.WEDNESDAY -> WED
                Calendar.THURSDAY -> THU
                Calendar.FRIDAY -> FRI
                Calendar.SATURDAY -> SAT
                else -> SUN
            }
        }

        fun formatDaysSummary(days: Set<ScheduleDay>): String {
            if (days.isEmpty()) return "Select at least one day"
            if (days.size == 7) return "Every day"
            if (days == setOf(MON, TUE, WED, THU, FRI)) return "Weekdays"
            if (days == setOf(SAT, SUN)) return "Weekends"
            // Sort in standard order: Mon..Sun or Sun..Sat
            val sortedDays = days.sortedBy { if (it == SUN) 7 else it.ordinal }
            return sortedDays.joinToString(", ") { it.displayName }
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
    val endHour: Int = 17, // 0..23
    val endMinute: Int = 0, // 0..59
    val endMode: ScheduleEndMode = ScheduleEndMode.AT_TIME,
    val requiresWifi: Boolean = false,
    val wifiSsid: String? = null,
    val daysOfWeek: Set<ScheduleDay>,
    val isEnabled: Boolean = true
) {
    val formattedStartTime: String
        get() {
            val amPm = if (startHour < 12) "AM" else "PM"
            val h12 = if (startHour == 0) 12 else if (startHour > 12) startHour - 12 else startHour
            return String.format(Locale.getDefault(), "%d:%02d %s", h12, startMinute, amPm)
        }

    val formattedEndTime: String
        get() {
            val amPm = if (endHour < 12) "AM" else "PM"
            val h12 = if (endHour == 0) 12 else if (endHour > 12) endHour - 12 else endHour
            return String.format(Locale.getDefault(), "%d:%02d %s", h12, endMinute, amPm)
        }

    val formattedTimeWindow: String
        get() {
            return if (endMode == ScheduleEndMode.ON_NFC_TAP) {
                "$formattedStartTime • On NFC Tap"
            } else {
                "$formattedStartTime - $formattedEndTime"
            }
        }

    val daysSummary: String
        get() = ScheduleDay.formatDaysSummary(daysOfWeek)

    fun overlapsWith(other: ScheduleRecord): Boolean {
        if (this.id == other.id) return false
        val sharedDays = this.daysOfWeek.intersect(other.daysOfWeek)
        if (sharedDays.isEmpty()) return false

        val thisStart = startHour * 60 + startMinute
        val thisEnd = if (endMode == ScheduleEndMode.ON_NFC_TAP) 24 * 60 else (endHour * 60 + endMinute)

        val otherStart = other.startHour * 60 + other.startMinute
        val otherEnd = if (other.endMode == ScheduleEndMode.ON_NFC_TAP) 24 * 60 else (other.endHour * 60 + other.endMinute)

        return thisStart < otherEnd && otherStart < thisEnd
    }

    fun isCurrentlyActive(
        nowEpochMs: Long = System.currentTimeMillis(),
        currentConnectedSsid: String? = null
    ): Boolean {
        if (!isEnabled) return false

        // WiFi condition check if enabled
        if (requiresWifi && !wifiSsid.isNullOrBlank()) {
            if (currentConnectedSsid == null || !currentConnectedSsid.equals(wifiSsid, ignoreCase = true)) {
                return false
            }
        }

        val cal = Calendar.getInstance().apply { timeInMillis = nowEpochMs }
        val currentDay = ScheduleDay.fromCalendarDay(cal.get(Calendar.DAY_OF_WEEK))
        val currentMinuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val startMinuteOfDay = startHour * 60 + startMinute
        val endMinuteOfDay = endHour * 60 + endMinute

        if (endMode == ScheduleEndMode.ON_NFC_TAP) {
            // Once started on schedule day after start time, it stays active until NFC unlocked
            return currentDay in daysOfWeek && currentMinuteOfDay >= startMinuteOfDay
        }

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
                    ScheduleDay.SUN -> ScheduleDay.SAT
                    ScheduleDay.MON -> ScheduleDay.SUN
                    ScheduleDay.TUE -> ScheduleDay.MON
                    ScheduleDay.WED -> ScheduleDay.TUE
                    ScheduleDay.THU -> ScheduleDay.WED
                    ScheduleDay.FRI -> ScheduleDay.THU
                    ScheduleDay.SAT -> ScheduleDay.FRI
                }
                yesterday in daysOfWeek
            } else {
                false
            }
        }
    }
}
