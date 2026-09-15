package websnag.elopenmike.com

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.nfc.NfcActionResolver
import websnag.elopenmike.com.core.nfc.NfcTagAction
import websnag.elopenmike.com.ui.overlay.handleOverlayNfcAction

class OverlayNfcActionTest {
    @Test fun unavailableStorageProducesFeedbackWithoutAttemptingUnlock() = runTest {
        val resolver = NfcActionResolver(FakeProfileRepository(), FakeNfcTagRepository(), storageUnreadable = { true })
        val messages = mutableListOf<String>()
        var finished = false
        val action = resolver.resolve("SYNTHETIC_TAG")
        assertEquals(NfcTagAction.StorageUnavailable, action)
        handleOverlayNfcAction(action,
            unlock = { error("unavailable storage must not attempt a tag/session write") },
            onUnlocked = { finished = true },
            onMessage = messages::add)
        assertEquals(listOf("Saved data has not loaded yet, so this tag was ignored."), messages)
        assertFalse(finished)
    }

    @Test fun deactivationFinishesOnlyAfterSuccessfulCommit() = runTest {
        val action = NfcTagAction.DeactivateProfile(Profile("synthetic", "Synthetic", isActive = true), "SYNTHETIC_TAG")
        for (committed in listOf(false, true)) {
            var finished = false
            handleOverlayNfcAction(action,
                unlock = { assertEquals(action, it); committed },
                onUnlocked = { finished = true },
                onMessage = { error("no storage-unavailable action was received") })
            assertEquals(committed, finished)
        }
    }
}
