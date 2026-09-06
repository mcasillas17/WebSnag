package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

/**
 * System-wide real-time enforcement state.
 * Evaluated by the EnforcementEngine and consumed by the AccessibilityService, OverlayActivity, and UI.
 */
@Serializable
data class EnforcementState(
    val isBlockingActive: Boolean = false,
    val activeProfile: Profile? = null,
    val filterMode: FilterMode = activeProfile?.filterMode ?: FilterMode.BLOCKLIST,
    val blockedPackages: Set<String> = emptySet(),
    val sessionStartedAtEpochMs: Long? = null,
    val emergencyCooldownActive: Boolean = false,
    val emergencyCooldownStartEpochMs: Long? = null,
    val emergencyCooldownDurationMs: Long = 0L,
    val lastBlockedPackageName: String? = null,
    val lastBlockedEpochMs: Long? = null,
    /**
     * Persisted state could not be read -- for example an initialization migration aborted and kept
     * the original bytes. This is never an active session; it is a distinct posture in which
     * [websnag.elopenmike.com.core.enforcement.EnforcementEngine.isPackageBlocked] fails closed and
     * the UI must offer recovery. The package decision deliberately lives only on the engine, which
     * owns the system-exemption set that keeps emergency calling, the dialer, the launcher and
     * WebSnag itself reachable.
     */
    val storageRecoveryRequired: Boolean = false,
    /**
     * The user deliberately released the [storageRecoveryRequired] lockdown because no retry could
     * repair it. Enforcement stays released only until persisted state loads, at which point the
     * engine re-arms by itself. The failure stays visible; only the blocking is paused.
     */
    val recoveryLockdownPaused: Boolean = false
) {
    /**
     * Whether unreadable persisted state is currently widening blocking. The single predicate every
     * consumer branches on, so the engine, the overlay's dismissal and the overlay's copy can never
     * disagree about whether the lockdown is still in force.
     */
    val recoveryLockdownInForce: Boolean get() = storageRecoveryRequired && !recoveryLockdownPaused

    /**
     * Calculates elapsed time since the current focus session started in milliseconds.
     */
    val elapsedSessionMillis: Long
        get() {
            if (!isBlockingActive || sessionStartedAtEpochMs == null) return 0L
            return (System.currentTimeMillis() - sessionStartedAtEpochMs).coerceAtLeast(0L)
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
