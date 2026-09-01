package websnag.elopenmike.com.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

interface TagIdentityProtector {
    fun fingerprint(rawUid: String): String?
}

/**
 * Boolean-only availability check for the existing NFC UID HMAC Keystore key. Deliberately never
 * exposes the alias, the key material, or any Keystore handle -- diagnostics may only ever learn
 * whether the key currently exists. Implementations must not create, rotate, or otherwise
 * provision the key merely to answer this probe: an absent key is reported as `false`, not
 * silently generated. A genuine Keystore access failure (unavailable provider, load failure,
 * etc.) is NOT the same thing as "absent" and must propagate with its original cause rather than
 * being conflated into `false`.
 */
interface KeystoreKeyAvailabilityProbe {
    fun isKeyAvailable(): Boolean
}

/**
 * Seam over the single `containsAlias` check [AndroidKeystoreTagIdentityProtector.isKeyAvailable]
 * needs, so its propagate-not-swallow behavior is unit-testable on a plain JVM -- where the real
 * "AndroidKeyStore" provider is never registered -- without touching an actual Android Keystore.
 * Public only because it appears in [AndroidKeystoreTagIdentityProtector]'s public constructor
 * signature; production code never needs to reference it directly (the default implementation
 * covers every real call site).
 */
fun interface KeystoreAliasCheck {
    fun containsAlias(alias: String): Boolean
}

class AndroidKeystoreTagIdentityProtector(
    private val aliasCheck: KeystoreAliasCheck = KeystoreAliasCheck { alias ->
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.containsAlias(alias)
    }
) : TagIdentityProtector, KeystoreKeyAvailabilityProbe {
    override fun fingerprint(rawUid: String): String? = runCatching {
        val key = getOrCreateKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        Base64.encodeToString(
            mac.doFinal(rawUid.trim().uppercase().toByteArray(Charsets.UTF_8)),
            Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE
        )
    }.getOrNull()

    /**
     * Whether the Keystore already holds the NFC UID HMAC key, without creating it. Only ever
     * returns a Boolean: never the alias, a handle, or key material. `false` is returned only
     * when [aliasCheck] *successfully* determines the alias is absent; any exception it throws
     * (Keystore unavailable, load failure, etc.) propagates unchanged instead of being conflated
     * with "not available".
     */
    override fun isKeyAvailable(): Boolean = aliasCheck.containsAlias(KEY_ALIAS)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"

        /**
         * Keystore alias for the NFC UID HMAC key. Internal (rather than private) only so an
         * instrumented test can read `containsAlias` directly, before and after calling
         * [isKeyAvailable], to prove the probe never creates the key as a side effect. Never
         * exported, logged, or otherwise surfaced outside this module.
         */
        internal const val KEY_ALIAS = "websnag.nfc.uid.hmac.v1"
    }
}
