package websnag.elopenmike.com.core.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleEndMode
import websnag.elopenmike.com.core.model.ScheduleRecord
import java.util.Calendar

class ScheduleAlarmCoordinator(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    val isExactAlarmAvailable: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleNext(schedules: List<ScheduleRecord>, nowEpochMs: Long = System.currentTimeMillis()) {
        val next = schedules.filter { it.isEnabled }
            .flatMap { schedule -> transitionTimes(schedule, nowEpochMs) }
            .filter { it > nowEpochMs }
            .minOrNull() ?: run {
                alarmManager.cancel(pendingIntent())
                return
            }
        val operation = pendingIntent()
        if (isExactAlarmAvailable) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
        }
    }

    private fun transitionTimes(schedule: ScheduleRecord, nowEpochMs: Long): List<Long> {
        val result = mutableListOf<Long>()
        val day = Calendar.getInstance().apply {
            timeInMillis = nowEpochMs
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1)
        }
        repeat(9) {
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

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(ACTION_RECONCILE)
            .setComponent(ComponentName(context, ScheduleAlarmReceiver::class.java))
            .setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val REQUEST_CODE = 4101
        const val ACTION_RECONCILE = "websnag.action.RECONCILE_SCHEDULES"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
