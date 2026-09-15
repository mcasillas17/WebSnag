package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

@Serializable
data class EmergencyRecovery(
    // These four fields are the released alpha format. Wall time is retained for readability
    // only; it never authorizes progress. Missing anchors restart the full validated duration.
    val profileId: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val intentionConfirmed: Boolean,
    val requestId: String? = null,
    val sessionId: String? = null,
    val startedAtElapsedMs: Long? = null,
    val bootId: String? = null
)
