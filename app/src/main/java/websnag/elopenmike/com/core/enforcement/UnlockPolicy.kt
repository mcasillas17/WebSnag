package websnag.elopenmike.com.core.enforcement

import websnag.elopenmike.com.core.model.UnlockCondition

sealed interface EndRequest {
    data class Nfc(val tagId: String, val isEnrolled: Boolean) : EndRequest
    data object Manual : EndRequest
    data class Emergency(val cooldownComplete: Boolean, val intentionConfirmed: Boolean) : EndRequest
    data object ScheduleEnded : EndRequest
}

enum class EndReason {
    NFC,
    MANUAL,
    EMERGENCY,
    SCHEDULE_END
}

data class EndEvent(val profileId: String, val reason: EndReason)

object UnlockPolicy {
    fun canEnd(condition: UnlockCondition, request: EndRequest): Boolean = when (request) {
        is EndRequest.Nfc -> request.isEnrolled && canUnlockWithTag(condition, request.tagId)
        EndRequest.Manual -> condition is UnlockCondition.ManualOnly ||
            (condition is UnlockCondition.DurationExpiry && condition.requiredTagId == null)
        // This checks policy, not proof of elapsed time. The engine rejects public emergency
        // end requests and commits only its own persisted request at its elapsed boundary.
        is EndRequest.Emergency -> request.cooldownComplete &&
            canStartEmergency(condition, request.intentionConfirmed)
        EndRequest.ScheduleEnded -> true
    }

    fun emergencyDurationMs(condition: UnlockCondition): Long? =
        (condition as? UnlockCondition.RequireNfcTag)?.emergencyCooldownMinutes
            ?.takeIf { it > 0 }?.toLong()?.times(60_000L)

    fun canStartEmergency(condition: UnlockCondition, intentionConfirmed: Boolean): Boolean =
        condition is UnlockCondition.RequireNfcTag && condition.allowEmergencyUnlock &&
            (!condition.requireIntentionPhrase || intentionConfirmed) &&
            emergencyDurationMs(condition) != null

    private fun canUnlockWithTag(condition: UnlockCondition, tagId: String): Boolean = when (condition) {
        is UnlockCondition.RequireNfcTag ->
            (condition.allowAnyEnrolledTag && condition.requiredTagId == null) ||
                condition.requiredTagId == tagId
        is UnlockCondition.DurationExpiry ->
            condition.allowEarlyNfcUnlock &&
                (condition.requiredTagId == null || condition.requiredTagId == tagId)
        UnlockCondition.ManualOnly -> false
    }
}
