package org.websnag.core.model

import kotlinx.serialization.Serializable

/**
 * Defines the necessary requirements or intentional friction to deactivate an active blocking profile.
 */
@Serializable
sealed interface UnlockCondition {

    /**
     * Requires tapping the specific NFC tag (or any designated tag) to unlock.
     * @param requiredTagUid Hardware UID or UUID of the tag, or null to accept any enrolled tag.
     * @param allowEmergencyUnlock Whether the deliberate friction emergency recovery mechanism is permitted.
     * @param emergencyCooldownMinutes Delay required before emergency unlock completes (default: 5 minutes).
     */
    @Serializable
    data class RequireNfcTag(
        val requiredTagUid: String? = null,
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
        val requiredTagUid: String? = null
    ) : UnlockCondition

    /**
     * Allows immediate manual deactivation directly from the WebSnag app.
     */
    @Serializable
    data object ManualOnly : UnlockCondition
}
