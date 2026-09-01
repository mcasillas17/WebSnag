package websnag.elopenmike.com.core.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import websnag.elopenmike.com.core.model.ScheduleRecord

class ScheduleAlarmCoordinator(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    val isExactAlarmAvailable: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleNext(schedules: List<ScheduleRecord>, nowEpochMs: Long = System.currentTimeMillis()) {
        val next = ScheduleTransitionCalculator.nextTransitionEpochMs(schedules, nowEpochMs) ?: run {
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
    }
}
