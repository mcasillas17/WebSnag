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

class AndroidKeystoreTagIdentityProtector : TagIdentityProtector {
    override fun fingerprint(rawUid: String): String? = runCatching {
        val key = getOrCreateKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        Base64.encodeToString(
            mac.doFinal(rawUid.trim().uppercase().toByteArray(Charsets.UTF_8)),
            Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE
        )
    }.getOrNull()

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

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "websnag.nfc.uid.hmac.v1"
    }
}
