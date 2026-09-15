package websnag.elopenmike.com

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import websnag.elopenmike.com.core.model.EnforcementState
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.ui.overlay.BlockOverlayScreen
import websnag.elopenmike.com.ui.recovery.LEGACY_UNLOCK_CONFIRMATION
import websnag.elopenmike.com.ui.recovery.PAUSE_BLOCKING_PHRASE
import websnag.elopenmike.com.ui.recovery.StorageRecoveryScreen

/** The user-facing surfaces shown while persisted state is unreadable. */
class StorageRecoveryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private var retries = 0
    private var conversions = 0
    private var pauses = 0

    private fun show() = composeRule.setContent {
        StorageRecoveryScreen(
            onRetry = { retries++ },
            onApproveLegacyUnlockConversion = { conversions++ },
            onPauseBlocking = { pauses++ },
            blockingPaused = false
        )
    }

    @Test fun retryIsReachableWithoutAnyConfirmation() {
        show()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
        assertEquals(0, conversions)
    }

    @Test fun replacingAnUnsupportedLockRequiresExplicitConfirmation() {
        show()
        composeRule.onNodeWithText("Replace the unsupported lock").assertIsNotEnabled()
        composeRule.onNodeWithText(LEGACY_UNLOCK_CONFIRMATION, substring = true).performClick()
        composeRule.onNodeWithText("Replace the unsupported lock").performClick()
        assertEquals(1, conversions)
        assertEquals(0, retries)
    }

    // The screen scrolls, so every interaction below the fold has to scroll to its node first.
    private fun typeIntention(text: String) =
        composeRule.onNode(hasSetTextAction()).performScrollTo().performTextInput(text)

    private fun pauseButton() =
        composeRule.onNodeWithText("Pause blocking until data loads").performScrollTo()

    @Test fun pausingBlockingRequiresTheExactIntentionPhrase() {
        show()
        pauseButton().assertIsNotEnabled()
        typeIntention("pause")
        pauseButton().assertIsNotEnabled()
        pauseButton().performClick()
        assertEquals(0, pauses)
    }

    @Test fun theExactIntentionPhraseReleasesTheLockdown() {
        show()
        typeIntention(PAUSE_BLOCKING_PHRASE)
        pauseButton().performClick()
        assertEquals(1, pauses)
        assertEquals(0, retries)
        assertEquals(0, conversions)
    }

    @Test fun blockOverlayExplainsRecoveryAndOffersNoUnlockItCannotHonor() {
        composeRule.setContent {
            BlockOverlayScreen(
                blockedPackageName = "invalid.synthetic.distraction",
                enforcementState = EnforcementState(storageRecoveryRequired = true),
                onGoHomeClicked = {},
                onStartEmergencyUnlock = {},
                onCancelEmergencyUnlock = {}
            )
        }
        composeRule.onNodeWithText("Saved Data Not Loaded").assertIsDisplayed()
        composeRule.onNodeWithText("Return to Home Screen").assertIsDisplayed()
        composeRule.onAllNodesWithText("Tap physical NFC tag to unlock").assertCountEquals(0)
        composeRule.onAllNodesWithText("Emergency Recovery (Intentional Friction)").assertCountEquals(0)
        composeRule.onAllNodesWithText("Active Profile (Allowlist Mode)").assertCountEquals(0)
    }

    @Test fun blockOverlayStopsClaimingRecoveryOnceTheLockdownIsPaused() {
        composeRule.setContent {
            BlockOverlayScreen(
                blockedPackageName = "com.instagram.android",
                enforcementState = EnforcementState(
                    storageRecoveryRequired = true,
                    recoveryLockdownPaused = true,
                    isBlockingActive = true,
                    activeProfile = Profile("loaded", "Loaded focus", isActive = true,
                        blockedPackages = setOf("com.instagram.android")),
                    blockedPackages = setOf("com.instagram.android")
                ),
                onGoHomeClicked = {},
                onStartEmergencyUnlock = {},
                onCancelEmergencyUnlock = {}
            )
        }
        composeRule.onAllNodesWithText("Saved Data Not Loaded").assertCountEquals(0)
        composeRule.onNodeWithText("Distraction Paused").assertIsDisplayed()
        composeRule.onNodeWithText("Loaded focus").assertIsDisplayed()
        composeRule.onNodeWithText("Active Profile (Blocklist)").assertIsDisplayed()
        // Pausing the extra lockdown does not make storage writable or authorize a session end.
        composeRule.onAllNodesWithText("Emergency Recovery (Intentional Friction)").assertCountEquals(0)
        composeRule.onAllNodesWithText("Tap physical NFC tag to unlock").assertCountEquals(0)
    }

    @Test fun blockOverlayOffersNoSessionAffordanceItCannotHonorDuringTheLockdown() {
        composeRule.setContent {
            BlockOverlayScreen(
                // Reachable when a read fails after a session had already loaded. Ending that
                // session needs a write, which cannot happen while the store is unreadable, so the
                // overlay must not imply it can be ended or show a running session alongside the
                // failure copy.
                blockedPackageName = "com.instagram.android",
                enforcementState = EnforcementState(
                    storageRecoveryRequired = true,
                    isBlockingActive = true,
                    sessionStartedAtEpochMs = 1_700_000_000_000L,
                    blockedPackages = setOf("com.instagram.android")
                ),
                onGoHomeClicked = {},
                onStartEmergencyUnlock = {},
                onCancelEmergencyUnlock = {}
            )
        }
        composeRule.onNodeWithText("Saved Data Not Loaded").assertIsDisplayed()
        composeRule.onNodeWithText("Return to Home Screen").assertIsDisplayed()
        composeRule.onAllNodesWithText("Emergency Recovery (Intentional Friction)").assertCountEquals(0)
        composeRule.onAllNodesWithText("Tap physical NFC tag to unlock").assertCountEquals(0)
        composeRule.onAllNodesWithText("Active Profile (Blocklist)").assertCountEquals(0)
    }
}
