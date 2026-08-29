package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.nfc.CredentialAssurance
import websnag.elopenmike.com.core.nfc.UnsupportedAuthenticatedTagVerifier
import websnag.elopenmike.com.core.privacy.PrivacyStatus

class PrivacyAndNfcAssuranceTest {

    @Test
    fun reportsNoInternetAndLabelsOrdinaryTagsAsLowAssurance() {
        val status = PrivacyStatus.fromDeclaredPermissions(setOf("android.permission.NFC"))

        assertFalse(status.internetPermissionDeclared)
        assertEquals(CredentialAssurance.LOW, UnsupportedAuthenticatedTagVerifier.assurance)
        assertTrue(UnsupportedAuthenticatedTagVerifier.unavailabilityReason.isNotBlank())
    }
}
