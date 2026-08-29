package websnag.elopenmike.com.core.activity

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import websnag.elopenmike.com.core.model.FocusSessionRecord
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

interface ActivitySigner {
    val publicKeyEncoded: ByteArray
    fun sign(payload: ByteArray): ByteArray
}

@Serializable
data class ActivityAttestationExport(
    val formatVersion: Int = FORMAT_VERSION,
    val algorithm: String = SIGNATURE_ALGORITHM,
    val publicKeyBase64: String,
    val records: List<FocusSessionRecord>,
    val signatureBase64: String
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

object ActivityAttestation {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun create(records: List<FocusSessionRecord>, signer: ActivitySigner): ActivityAttestationExport {
        val orderedRecords = records.sortedBy { it.id }
        val payload = canonicalPayload(orderedRecords)
        val signature = signer.sign(payload)
        return ActivityAttestationExport(
            publicKeyBase64 = Base64.getEncoder().encodeToString(signer.publicKeyEncoded),
            records = orderedRecords,
            signatureBase64 = Base64.getEncoder().encodeToString(signature)
        )
    }

    fun verify(export: ActivityAttestationExport): Boolean {
        if (export.formatVersion != ActivityAttestationExport.FORMAT_VERSION ||
            export.algorithm != ActivityAttestationExport.SIGNATURE_ALGORITHM ||
            export.records != export.records.sortedBy { it.id }
        ) return false
        return try {
            val key = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(export.publicKeyBase64))
            )
            Signature.getInstance(ActivityAttestationExport.SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(canonicalPayload(export.records))
                verify(Base64.getDecoder().decode(export.signatureBase64))
            }
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun canonicalPayload(records: List<FocusSessionRecord>): ByteArray {
        return json.encodeToString(AttestationPayload(records = records)).encodeToByteArray()
    }

    @Serializable
    private data class AttestationPayload(
        val formatVersion: Int = ActivityAttestationExport.FORMAT_VERSION,
        val records: List<FocusSessionRecord>
    )
}
