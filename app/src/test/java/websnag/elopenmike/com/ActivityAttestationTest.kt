package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.activity.ActivityAttestation
import websnag.elopenmike.com.core.activity.ActivitySigner
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.FocusSessionRecord
import java.security.KeyPairGenerator
import java.security.Signature

class ActivityAttestationTest {

    @Test
    fun signsAndVerifiesRecordsInDeterministicIdOrder() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = object : ActivitySigner {
            override val publicKeyEncoded: ByteArray = keyPair.public.encoded

            override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }
        }
        val records = listOf(
            FocusSessionRecord("b", "profile", "Deep Work", FilterMode.BLOCKLIST, 20, 30, 10),
            FocusSessionRecord("a", "profile", "Deep Work", FilterMode.BLOCKLIST, 0, 10, 10)
        )

        val export = ActivityAttestation.create(records, signer)

        assertTrue(ActivityAttestation.verify(export))
        assertEquals(listOf("a", "b"), export.records.map { it.id })
    }

    @Test
    fun rejectsAChangedRecord() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = object : ActivitySigner {
            override val publicKeyEncoded: ByteArray = keyPair.public.encoded
            override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }
        }
        val export = ActivityAttestation.create(
            listOf(FocusSessionRecord("a", "profile", "Deep Work", FilterMode.BLOCKLIST, 0, 10, 10)),
            signer
        )

        assertTrue(!ActivityAttestation.verify(export.copy(records = export.records.map { it.copy(durationSeconds = 99) })))
    }
}
