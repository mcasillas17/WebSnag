package org.websnag.core.model

import kotlinx.serialization.Serializable

/**
 * A user-configured distraction blocking profile.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val description: String = "",
    val colorHex: String = "#3B82F6",
    val iconName: String = "shield",
    val blockedPackages: Set<String> = emptySet(),
    val linkedTagUid: String? = null,
    val unlockCondition: UnlockCondition = UnlockCondition.RequireNfcTag(requiredTagUid = linkedTagUid),
    val isActive: Boolean = false,
    val activatedAtEpochMs: Long? = null,
    val triggers: List<Trigger> = emptyList()
) {
    /**
     * Checks whether an incoming NFC tag UID can unlock/deactivate this profile.
     */
    fun canUnlockWithTag(scannedUidHex: String): Boolean {
        if (!isActive) return false
        return when (val condition = unlockCondition) {
            is UnlockCondition.RequireNfcTag -> {
                if (condition.requiredTagUid == null) true
                else condition.requiredTagUid.equals(scannedUidHex, ignoreCase = true)
            }
            is UnlockCondition.DurationExpiry -> {
                if (!condition.allowEarlyNfcUnlock) false
                else condition.requiredTagUid == null || condition.requiredTagUid.equals(scannedUidHex, ignoreCase = true)
            }
            UnlockCondition.ManualOnly -> true
        }
    }
}
