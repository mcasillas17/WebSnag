package org.websnag.core.model

import kotlinx.serialization.Serializable

/**
 * Record of a completed focus session for activity tracking and metrics.
 */
@Serializable
data class FocusSessionRecord(
    val id: String,
    val profileId: String,
    val profileName: String,
    val filterMode: FilterMode = FilterMode.BLOCKLIST,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val durationSeconds: Long,
    val interceptionsPrevented: Int = 0
)
