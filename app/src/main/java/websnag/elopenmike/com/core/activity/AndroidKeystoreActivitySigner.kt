package websnag.elopenmike.com.core.activity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class AndroidKeystoreActivitySigner : ActivitySigner {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build()
                )
                generateKeyPair()
            }
        }
    }

    override val publicKeyEncoded: ByteArray
        get() = keyStore.getCertificate(KEY_ALIAS).publicKey.encoded

    override fun sign(payload: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("The activity-attestation key is unavailable.")
        return Signature.getInstance(ActivityAttestationExport.SIGNATURE_ALGORITHM).run {
            initSign(privateKey as java.security.PrivateKey)
            update(payload)
            sign()
        }
    }

    companion object {
        const val KEY_ALIAS = "websnag.activity_attestation.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
