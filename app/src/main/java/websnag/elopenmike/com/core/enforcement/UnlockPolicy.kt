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
        is EndRequest.Emergency -> request.cooldownComplete &&
            request.intentionConfirmed &&
            (condition as? UnlockCondition.RequireNfcTag)?.allowEmergencyUnlock == true
        EndRequest.ScheduleEnded -> true
    }

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
