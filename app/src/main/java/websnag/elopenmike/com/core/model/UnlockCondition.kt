package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

/**
 * Defines the necessary requirements or intentional friction to deactivate an active blocking profile.
 */
@Serializable
sealed interface UnlockCondition {

    /**
     * Requires tapping a specific enrolled tag to unlock. An any-enrolled-tag policy must be
     * explicitly selected; a missing binding never broadens authorization.
     * @param requiredTagId Stable enrolled-tag identifier, not an NFC hardware UID.
     * @param allowEmergencyUnlock Whether the deliberate friction emergency recovery mechanism is permitted.
     * @param emergencyCooldownMinutes Delay required before emergency unlock completes (default: 5 minutes).
     */
    @Serializable
    data class RequireNfcTag(
        val requiredTagId: String? = null,
        val allowAnyEnrolledTag: Boolean = false,
        val allowEmergencyUnlock: Boolean = true,
        val emergencyCooldownMinutes: Int = 5,
        val requireIntentionPhrase: Boolean = true
    ) : UnlockCondition

    /**
     * Unlocks automatically after a specified duration in milliseconds.
     */
    @Serializable
    data class DurationExpiry(
        val durationMinutes: Int,
        val allowEarlyNfcUnlock: Boolean = true,
        val requiredTagId: String? = null
    ) : UnlockCondition

    /**
     * Allows immediate manual deactivation directly from the WebSnag app.
     */
    @Serializable
    data object ManualOnly : UnlockCondition
}
