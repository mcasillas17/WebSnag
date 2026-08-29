package websnag.elopenmike.com.core.nfc

enum class CredentialAssurance {
    LOW,
    HARDWARE_AUTHENTICATED
}

data class TagCredential(
    val tagUidHex: String,
    val challenge: ByteArray,
    val response: ByteArray
)

interface TagCredentialVerifier {
    val assurance: CredentialAssurance
    suspend fun verify(credential: TagCredential): Boolean
}

object UnsupportedAuthenticatedTagVerifier : TagCredentialVerifier {
    override val assurance = CredentialAssurance.LOW
    const val unavailabilityReason =
        "Authenticated tag hardware is not enabled in this build. UID and static NDEF tags are low assurance and replayable."

    override suspend fun verify(credential: TagCredential): Boolean = false
}
