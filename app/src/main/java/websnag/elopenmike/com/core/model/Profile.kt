package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

/**
 * Defines whether a profile blocks listed apps or blocks everything except listed apps.
 */
@Serializable
enum class FilterMode {
    /**
     * Standard blocklist: Selected packages are blocked, all other apps are allowed.
     */
    BLOCKLIST,

    /**
     * Strict allowlist ("Dumbphone Mode"): Only selected packages are allowed, all other apps are blocked.
     */
    ALLOWLIST
}

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
    val filterMode: FilterMode = FilterMode.BLOCKLIST,
    val blockedPackages: Set<String> = emptySet(),
    val linkedTagId: String? = null,
    val unlockCondition: UnlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = linkedTagId),
    val isActive: Boolean = false,
    val activatedAtEpochMs: Long? = null,
    val triggers: List<Trigger> = emptyList()
) {
    /**
     * Checks whether an enrolled tag identifier can unlock/deactivate this profile.
     */
    fun canUnlockWithTag(enrolledTagId: String): Boolean {
        if (!isActive) return false
        return when (val condition = unlockCondition) {
            is UnlockCondition.RequireNfcTag -> {
                (condition.allowAnyEnrolledTag && condition.requiredTagId == null) ||
                    condition.requiredTagId == enrolledTagId
            }
            is UnlockCondition.DurationExpiry -> {
                if (!condition.allowEarlyNfcUnlock) false
                else condition.requiredTagId == null || condition.requiredTagId == enrolledTagId
            }
            UnlockCondition.ManualOnly -> false
        }
    }
}
