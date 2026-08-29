package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

/**
 * Metadata representing a physical NFC tag enrolled in WebSnag.
 */
@Serializable
data class NfcTagRecord(
    val id: String,
    val uidFingerprint: String,
    val label: String,
    val customPayload: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val lastUsedEpochMs: Long? = null,
    val description: String = ""
) {
    /**
     * Raw hardware UIDs are never persisted. Matching is performed by NfcTagRepository after
     * deriving a device-keyed fingerprint from the scanned UID.
     */
}
