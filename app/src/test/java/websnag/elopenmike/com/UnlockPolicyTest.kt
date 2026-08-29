package websnag.elopenmike.com

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.UnlockPolicy
import websnag.elopenmike.com.core.model.UnlockCondition

class UnlockPolicyTest {

    @Test
    fun `nfc unlock requires a known tag bound to the active profile`() {
        val condition = UnlockCondition.RequireNfcTag(requiredTagId = "desk-tag")

        assertTrue(
            UnlockPolicy.canEnd(
                condition,
                EndRequest.Nfc(tagId = "desk-tag", isEnrolled = true)
            )
        )
        assertFalse(
            UnlockPolicy.canEnd(
                condition,
                EndRequest.Nfc(tagId = "desk-tag", isEnrolled = false)
            )
        )
        assertFalse(
            UnlockPolicy.canEnd(
                condition,
                EndRequest.Nfc(tagId = "unknown-tag", isEnrolled = true)
            )
        )
    }

    @Test
    fun `manual-only profiles never accept an NFC tap`() {
        assertFalse(
            UnlockPolicy.canEnd(
                UnlockCondition.ManualOnly,
                EndRequest.Nfc(tagId = "known-tag", isEnrolled = true)
            )
        )
        assertTrue(UnlockPolicy.canEnd(UnlockCondition.ManualOnly, EndRequest.Manual))
    }

    @Test
    fun `emergency recovery requires the configured phrase and completed cooldown`() {
        val condition = UnlockCondition.RequireNfcTag(
            requiredTagId = "desk-tag",
            allowEmergencyUnlock = true,
            requireIntentionPhrase = true
        )

        assertFalse(
            UnlockPolicy.canEnd(
                condition,
                EndRequest.Emergency(cooldownComplete = false, intentionConfirmed = true)
            )
        )
        assertFalse(
            UnlockPolicy.canEnd(
                condition,
                EndRequest.Emergency(cooldownComplete = true, intentionConfirmed = false)
            )
        )
        assertTrue(
            UnlockPolicy.canEnd(
                condition,
                EndRequest.Emergency(cooldownComplete = true, intentionConfirmed = true)
            )
        )
    }

    @Test
    fun `duration profiles without an NFC binding can be ended manually`() {
        assertTrue(
            UnlockPolicy.canEnd(
                UnlockCondition.DurationExpiry(durationMinutes = 30),
                EndRequest.Manual
            )
        )
    }
}
