package websnag.elopenmike.com.core.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import websnag.elopenmike.com.WebSnagApp

/**
 * Reconciles schedules and reschedules the next alarm for an action in the receiving component's
 * own [acceptedActions]. A missing, unknown or other-component action returns before `goAsync()`
 * and touches no schedule state, so the exported system receiver can never run the internal alarm
 * action and the internal receiver never runs a system action.
 *
 * Matching the action is not sender authentication: an intent carries whatever action its sender
 * wrote. It only keeps each component to the deliveries it is declared for.
 */
abstract class ScheduleReconcileReceiver : BroadcastReceiver() {
    protected abstract val acceptedActions: Set<String>

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in acceptedActions) return
        // Null only when onReceive is invoked outside a framework broadcast; nothing to finish then.
        val pendingResult: PendingResult? = goAsync()
        val scheduleManager = (context.applicationContext as WebSnagApp).scheduleManager
        scheduleManager.reconcileNow {
            scheduleManager.reschedule()
            pendingResult?.finish()
        }
    }
}
