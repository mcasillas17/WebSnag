package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

/**
 * Metadata representing a physical NFC tag enrolled in WebSnag.
 */
@Serializable
data class NfcTagRecord(
    val id: String,
    val uidHex: String,
    val label: String,
    val customPayload: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val lastUsedEpochMs: Long? = null,
    val description: String = ""
) {
    /**
     * Matches against a scanned tag by checking hardware UID or custom WebSnag NDEF payload.
     */
    fun matches(scannedUidHex: String, scannedPayload: String? = null): Boolean {
        if (uidHex.equals(scannedUidHex, ignoreCase = true)) return true
        if (customPayload != null && scannedPayload != null && customPayload == scannedPayload) return true
        return false
    }
}
