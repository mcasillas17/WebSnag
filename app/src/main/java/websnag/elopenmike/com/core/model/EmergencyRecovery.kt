package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

@Serializable
data class EmergencyRecovery(
    val profileId: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val intentionConfirmed: Boolean
) {
    val completesAtEpochMs: Long
        get() = startedAtEpochMs + durationMs
}
