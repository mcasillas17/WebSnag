package websnag.elopenmike.com.core.schedule

import kotlinx.serialization.Serializable
import websnag.elopenmike.com.core.model.ScheduleRecord
import java.util.Calendar

@Serializable
data class ScheduleOccurrence(
    val scheduleId: String,
    val occurrenceStartEpochMs: Long,
    val occurrenceEndEpochMs: Long?,
    val profileId: String = "",
    val dismissed: Boolean = false,
    val endReason: String? = null
)

object ScheduleReconciler {
    enum class Decision { ACTIVATE, KEEP_ACTIVE, END, NONE }

    fun occurrenceFor(schedule: ScheduleRecord, nowEpochMs: Long): ScheduleOccurrence? {
        if (!schedule.isCurrentlyActive(nowEpochMs)) return null
        val calendar = Calendar.getInstance().apply { timeInMillis = nowEpochMs }
        calendar.set(Calendar.HOUR_OF_DAY, schedule.startHour)
        calendar.set(Calendar.MINUTE, schedule.startMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis > nowEpochMs) calendar.add(Calendar.DAY_OF_YEAR, -1)
        val start = calendar.timeInMillis
        val end = if (schedule.endMode == websnag.elopenmike.com.core.model.ScheduleEndMode.AT_TIME) {
            calendar.apply {
                set(Calendar.HOUR_OF_DAY, schedule.endHour)
                set(Calendar.MINUTE, schedule.endMinute)
                if (timeInMillis <= start) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        } else {
            null
        }
        return ScheduleOccurrence(schedule.id, start, end, schedule.profileId)
    }

    fun shouldActivate(schedule: ScheduleRecord, stored: ScheduleOccurrence?, nowEpochMs: Long): Boolean {
        val current = occurrenceFor(schedule, nowEpochMs) ?: return false
        return stored?.let { it.scheduleId != current.scheduleId || it.occurrenceStartEpochMs != current.occurrenceStartEpochMs || !it.dismissed } ?: true
    }

    fun reconcile(schedule: ScheduleRecord, occurrence: ScheduleOccurrence?, nowEpochMs: Long): Decision {
        if (occurrence == null) return if (schedule.isCurrentlyActive(nowEpochMs)) Decision.ACTIVATE else Decision.NONE
        if (occurrence.occurrenceEndEpochMs?.let { nowEpochMs >= it } == true) return Decision.END
        val current = occurrenceFor(schedule, nowEpochMs)
        if (current == null) return Decision.END
        if (current.occurrenceStartEpochMs != occurrence.occurrenceStartEpochMs) return Decision.END
        return if (occurrence.dismissed) Decision.NONE else Decision.KEEP_ACTIVE
    }
}
