package org.websnag.core.model

import kotlinx.serialization.Serializable

/**
 * System-wide real-time enforcement state.
 * Evaluated by the EnforcementEngine and consumed by the AccessibilityService, OverlayActivity, and UI.
 */
@Serializable
data class EnforcementState(
    val isBlockingActive: Boolean = false,
    val activeProfile: Profile? = null,
    val blockedPackages: Set<String> = emptySet(),
    val emergencyCooldownActive: Boolean = false,
    val emergencyCooldownStartEpochMs: Long? = null,
    val emergencyCooldownDurationMs: Long = 0L,
    val lastBlockedPackageName: String? = null,
    val lastBlockedEpochMs: Long? = null
) {
    /**
     * Fast check whether a specific package is currently blocked.
     */
    fun isPackageBlocked(packageName: String): Boolean {
        if (!isBlockingActive) return false
        return blockedPackages.contains(packageName)
    }

    /**
     * Calculates remaining emergency cooldown in milliseconds.
     */
    val remainingEmergencyMs: Long
        get() {
            if (!emergencyCooldownActive || emergencyCooldownStartEpochMs == null) return 0L
            val elapsed = System.currentTimeMillis() - emergencyCooldownStartEpochMs
            return (emergencyCooldownDurationMs - elapsed).coerceAtLeast(0L)
        }
}
