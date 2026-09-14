package websnag.elopenmike.com.core.schedule

/** Unexported target of the alarm [ScheduleAlarmCoordinator] schedules; accepts only that alarm. */
class ScheduleAlarmReceiver : ScheduleReconcileReceiver() {
    override val acceptedActions = setOf(ACTION_RECONCILE)

    companion object {
        /** Carried by alarms that are already scheduled, so the value must not change. */
        const val ACTION_RECONCILE = "websnag.action.RECONCILE_SCHEDULES"
    }
}
