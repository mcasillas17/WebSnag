package websnag.elopenmike.com.core.schedule

import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleEndMode
import websnag.elopenmike.com.core.model.ScheduleRecord
import java.util.Calendar

/**
 * Pure JVM computation of the next schedule start/end transition timestamp. Contains no Android
 * framework dependency so it is directly exercisable by JVM unit tests; [ScheduleAlarmCoordinator]
 * delegates to this object to decide what to hand the OS AlarmManager.
 */
object ScheduleTransitionCalculator {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val LOOKAHEAD_DAYS = 9

    /**
     * Earliest transition timestamp strictly greater than [nowEpochMs], across every enabled
     * schedule in [schedules]. A disabled schedule never contributes a transition. An
     * [ScheduleEndMode.ON_NFC_TAP] schedule only ever contributes a start transition, since it has
     * no fixed end time. Returns null when no enabled schedule produces a transition within the
     * [LOOKAHEAD_DAYS]-day lookahead window.
     */
    fun nextTransitionEpochMs(schedules: List<ScheduleRecord>, nowEpochMs: Long): Long? {
        return schedules.filter { it.isEnabled }
            .flatMap { schedule -> transitionTimes(schedule, nowEpochMs) }
            .filter { it > nowEpochMs }
            .minOrNull()
    }

    private fun transitionTimes(schedule: ScheduleRecord, nowEpochMs: Long): List<Long> {
        val result = mutableListOf<Long>()
        val day = Calendar.getInstance().apply {
            timeInMillis = nowEpochMs
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1)
        }
        repeat(LOOKAHEAD_DAYS) {
            val scheduleDay = ScheduleDay.fromCalendarDay(day.get(Calendar.DAY_OF_WEEK))
            if (scheduleDay in schedule.daysOfWeek) {
                result += day.atTime(schedule.startHour, schedule.startMinute)
                if (schedule.endMode == ScheduleEndMode.AT_TIME) {
                    val endDay = day.clone() as Calendar
                    result += endDay.atTime(schedule.endHour, schedule.endMinute).let { end ->
                        if (end <= day.atTime(schedule.startHour, schedule.startMinute)) end + DAY_MS else end
                    }
                }
            }
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    private fun Calendar.atTime(hour: Int, minute: Int): Long = apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }.timeInMillis
}
