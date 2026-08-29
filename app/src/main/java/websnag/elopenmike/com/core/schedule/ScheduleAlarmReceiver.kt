package websnag.elopenmike.com.core.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import websnag.elopenmike.com.WebSnagApp

open class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext as WebSnagApp
        app.scheduleManager.reconcileNow {
            app.scheduleManager.reschedule()
            pendingResult.finish()
        }
    }
}
