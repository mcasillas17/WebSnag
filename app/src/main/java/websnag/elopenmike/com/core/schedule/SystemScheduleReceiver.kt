package websnag.elopenmike.com.core.schedule

import android.content.Intent

/** Exported for the platform events its manifest filter declares; accepts exactly those actions. */
class SystemScheduleReceiver : ScheduleReconcileReceiver() {
    override val acceptedActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_MY_PACKAGE_REPLACED
    )
}
