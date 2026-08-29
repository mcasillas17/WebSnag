package websnag.elopenmike.com

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.activity.ActivityAttestation
import websnag.elopenmike.com.core.activity.AndroidKeystoreActivitySigner
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.FocusSessionRecord
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreActivitySignerTest {

    @Test
    fun persistsItsInstallKeyAndRejectsChangedActivityData() {
        val firstSigner = AndroidKeystoreActivitySigner()
        val secondSigner = AndroidKeystoreActivitySigner()
        val export = ActivityAttestation.create(
            listOf(
                FocusSessionRecord(
                    id = "session-1",
                    profileId = "profile-1",
                    profileName = "Deep Work",
                    filterMode = FilterMode.BLOCKLIST,
                    startTimeEpochMs = 10,
                    endTimeEpochMs = 20,
                    durationSeconds = 10
                )
            ),
            firstSigner
        )

        assertArrayEquals(firstSigner.publicKeyEncoded, secondSigner.publicKeyEncoded)
        assertTrue(ActivityAttestation.verify(export))
        assertFalse(
            ActivityAttestation.verify(
                export.copy(records = export.records.map { it.copy(interceptionsPrevented = 1) })
            )
        )
    }

    @Test
    fun regeneratesAfterKeyLossWhilePriorAttestationsRemainVerifiable() {
        val originalSigner = AndroidKeystoreActivitySigner()
        val priorExport = ActivityAttestation.create(emptyList(), originalSigner)
        val originalPublicKey = originalSigner.publicKeyEncoded
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        keyStore.deleteEntry(AndroidKeystoreActivitySigner.KEY_ALIAS)
        val regeneratedSigner = AndroidKeystoreActivitySigner()

        assertFalse(originalPublicKey.contentEquals(regeneratedSigner.publicKeyEncoded))
        assertTrue(ActivityAttestation.verify(priorExport))
    }
}
