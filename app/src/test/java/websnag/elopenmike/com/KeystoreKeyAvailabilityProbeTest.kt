package websnag.elopenmike.com

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.data.AndroidKeystoreTagIdentityProtector
import websnag.elopenmike.com.core.data.KeystoreAliasCheck
import java.io.IOException
import java.security.KeyStoreException

/**
 * Pure JVM proof that [AndroidKeystoreTagIdentityProtector.isKeyAvailable] never conflates a
 * genuine Keystore access failure with "key absent". `false` may only ever come from a
 * *successful* alias check that finds nothing; any other failure (Keystore unavailable, load
 * failure, etc.) must propagate with its original cause rather than being swallowed. The real
 * "AndroidKeyStore" provider does not exist on a plain JVM, so [KeystoreAliasCheck] is injected
 * here to exercise both outcomes without an Android runtime.
 */
class KeystoreKeyAvailabilityProbeTest {

    @Test
    fun isKeyAvailableReturnsFalseOnlyForASuccessfulAbsenceCheck() {
        val probe = AndroidKeystoreTagIdentityProtector(aliasCheck = KeystoreAliasCheck { false })
        assertFalse(probe.isKeyAvailable())
    }

    @Test
    fun isKeyAvailableReturnsTrueForASuccessfulPresenceCheck() {
        val probe = AndroidKeystoreTagIdentityProtector(aliasCheck = KeystoreAliasCheck { true })
        assertTrue(probe.isKeyAvailable())
    }

    @Test
    fun isKeyAvailablePropagatesTheOriginalExceptionRatherThanSwallowingItToFalse() {
        val originalFailure = IOException("simulated keystore load failure")
        val probe = AndroidKeystoreTagIdentityProtector(
            aliasCheck = KeystoreAliasCheck { throw originalFailure }
        )

        val thrown = assertThrows(IOException::class.java) { probe.isKeyAvailable() }
        assertSame(originalFailure, thrown)
    }

    @Test
    fun realKeystoreProviderIsUnavailableOnAPlainJvmAndThrowsRatherThanReturningFalse() {
        // No fake injected here: this exercises the real KeyStore.getInstance("AndroidKeyStore")
        // path, which has no registered provider on a plain JVM. Before this fix that failure was
        // silently conflated into `false`; it must now propagate instead.
        val probe = AndroidKeystoreTagIdentityProtector()
        assertThrows(KeyStoreException::class.java) { probe.isKeyAvailable() }
    }
}
