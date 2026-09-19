package websnag.elopenmike.com

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import websnag.elopenmike.com.ui.dashboard.DashboardScreen
import websnag.elopenmike.com.ui.dashboard.DashboardViewModel
import websnag.elopenmike.com.ui.theme.WebSnagTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import websnag.elopenmike.com.core.data.EnforcementSnapshot
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.ui.overlay.BlockOverlayActivity

/**
 * Real Android intent delivery plus software decoded-scan callback wiring in both Activities.
 * Callback tests do not fabricate a Tag or ReaderMode event and are not physical-hardware proof.
 * UID/NDEF extras remain untrusted input, not evidence of a physical scan or secure hardware.
 * Run only on the suite's disposable installation; never clear app stores or delete Keystore aliases.
 */
class NfcAuthorizationActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<WebSnagApp>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val profileId = "synthetic-nfc-boundary-${UUID.randomUUID()}"
    private val rawUid = "04A1B2C3D4E502"
    private val unknownRawUid = "04A1B2C3D4E5FE"
    private val tagLabel = "Synthetic boundary tag"
    private var tag: NfcTagRecord? = null
    private val scans = AtomicInteger()
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before fun setup(): Unit = runBlocking {
        withTimeout(10_000) {
            app.profileRepository.profilesFlow.first { it.isNotEmpty() }
            check(app.profileRepository.readEnforcementSnapshot().activeProfile == null) {
                "Activity tests require a disposable installation with no active session."
            }
            check(app.nfcTagRepository.getTagForUid(rawUid) == null)
            check(app.nfcTagRepository.getTagForUid(unknownRawUid) == null)
            val enrolled = checkNotNull(app.nfcTagRepository.enrollTag(rawUid, tagLabel, null, ""))
            tag = enrolled
            app.profileRepository.saveProfile(Profile(
                id = profileId,
                name = "Synthetic boundary focus",
                linkedTagId = enrolled.id,
                blockedPackages = setOf("invalid.synthetic.blocked"),
                unlockCondition = UnlockCondition.RequireNfcTag(
                    requiredTagId = enrolled.id, allowEmergencyUnlock = false
                )
            ))
            check(app.enforcementEngine.tryActivateProfile(profileId))
            app.enforcementEngine.enforcementState.first { it.activeProfile?.id == profileId }
        }
        // UNDISPATCHED subscribes before the first Activity/Intent is delivered.
        scanScope.launch(start = CoroutineStart.UNDISPATCHED) {
            app.nfcManager.scannedTagFlow.collect { scans.incrementAndGet() }
        }
    }

    @After fun cleanup(): Unit = runBlocking {
        scanScope.coroutineContext[Job]!!.cancelAndJoin()
        withTimeout(10_000) {
            if (app.enforcementEngine.enforcementState.value.activeProfile?.id == profileId) {
                assertTrue(app.enforcementEngine.requestEnd(profileId, EndRequest.ScheduleEnded))
            }
            app.profileRepository.deleteProfile(profileId)
            tag?.let { app.nfcTagRepository.deleteTag(it.id) }
        }
    }

    @Test fun forgedLaunchIntentsCannotPublishScanOrUnlock() {
        assertLauncherExported()
        val before = snapshot()
        forgedIntents().forEachIndexed { index, intent ->
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity {
                    assertTrue(it.intent.getIntExtra(DELIVERY_MARKER, -1) == index)
                }
                assertIgnored(before)
            }
        }
    }

    @Test fun forgedOnNewIntentsCannotPublishScanOrUnlock() {
        assertLauncherExported()
        val before = snapshot()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: MainActivity
            lateinit var launchIntent: Intent
            scenario.onActivity {
                original = it
                launchIntent = Intent(it.intent)
            }
            try {
                forgedIntents().forEachIndexed { index, intent ->
                    app.startActivity(intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    ))
                    // setIntent happens in MainActivity.onNewIntent. Observe delivery, not a guessed delay.
                    compose.waitUntil(10_000) {
                        var delivered = false
                        scenario.onActivity {
                            delivered = it.intent.getIntExtra(DELIVERY_MARKER, -1) == index
                        }
                        delivered
                    }
                    scenario.onActivity { assertTrue("delivery must reuse the Activity", it === original) }
                    assertIgnored(before)
                }
            } finally {
                // ActivityScenario filters lifecycle events by its launch intent. Restore only
                // after the delivery assertions, so close() can observe the real DESTROYED event.
                instrumentation.runOnMainSync { original.intent = launchIntent }
            }
        }
    }

    @Test fun tagHubShowsProtectedIdentityWithoutRawUid() {
        val before = snapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasText("NFC Hub") and hasClickAction()).performClick()
            compose.waitUntil(10_000) { visibleText().any { it == tagLabel } }
            val text = visibleText()
            // Boolean-only failures: never use raw identity as a selector, expected value, or dump.
            assertFalse("tag UI must not expose hardware identity", text.any { it.contains(rawUid, ignoreCase = true) })
            assertTrue(text.any { it == "Hardware ID protected on this device" })
            assertProtectedSession(before)

            // Scope the action to this fixture's card; never select a global/first Delete button.
            compose.onNodeWithText(tagLabel).onParent().onChildren()
                .filterToOne(hasContentDescription("Delete") and hasClickAction()).performClick()
            val refusal = "End the focus session before changing enrolled tag identities or deleting all data."
            compose.waitUntil(10_000) { visibleText().any { it == refusal } }
            val afterRefusal = visibleText()
            assertFalse("refusal UI must not expose hardware identity",
                afterRefusal.any { it.contains(rawUid, ignoreCase = true) })
            assertTrue(afterRefusal.any { it == tagLabel })
            compose.onNodeWithText(refusal).assertIsDisplayed()
            assertProtectedSession(before)
        }
    }

    @Test fun mainDecodedScanCallbackRejectsUnknownAndUnlocksExpectedTag() {
        val before = snapshot()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var callback: Job
            scenario.onActivity { callback = it.handleScannedTag(unknownRawUid, null) }
            awaitCallback(callback)
            assertProtectedSession(before)
            compose.onNodeWithText("FOCUS ACTIVE").assertIsDisplayed()

            scenario.onActivity { callback = it.handleScannedTag(rawUid, null) }
            awaitCallback(callback)
            assertSessionEndedBySoftwareCallback()
        }
    }

    @Test fun overlayDecodedScanCallbackRejectsUnknownAndUnlocksExpectedTag() {
        val before = snapshot()
        val intent = Intent(app, BlockOverlayActivity::class.java)
            .putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, "invalid.synthetic.blocked")
        ActivityScenario.launch<BlockOverlayActivity>(intent).use { scenario ->
            lateinit var callback: Job
            scenario.onActivity { activity ->
                callback = activity.lifecycleScope.launch { activity.handleScannedTag(unknownRawUid, null) }
            }
            awaitCallback(callback)
            assertProtectedSession(before)
            compose.onNodeWithText("Tap physical NFC tag to unlock").assertIsDisplayed()

            scenario.onActivity { activity ->
                callback = activity.lifecycleScope.launch { activity.handleScannedTag(rawUid, null) }
            }
            awaitCallback(callback)
            assertSessionEndedBySoftwareCallback()
        }
    }

    private fun awaitCallback(callback: Job): Unit = runBlocking {
        withTimeout(10_000) { callback.join() }
    }

    private fun assertSessionEndedBySoftwareCallback() {
        val persisted = snapshot()
        assertTrue(persisted.activeProfile == null)
        assertTrue(persisted.recovery == null)
        val state = app.enforcementEngine.enforcementState.value
        assertFalse(state.isBlockingActive)
        assertTrue(state.activeProfile == null)
        assertTrue(state.emergencyRecovery == null)
        assertFalse(app.enforcementEngine.isPackageBlocked("invalid.synthetic.blocked"))
        runBlocking {
            withTimeout(10_000) {
                val profile = app.profileRepository.getProfileById(profileId)
                assertTrue(profile != null && !profile.isActive && profile.sessionId == null)
                val enrolled = app.nfcTagRepository.getTags().singleOrNull { it.id == tag!!.id }
                assertTrue(enrolled?.lastUsedEpochMs != null)
            }
        }
        assertEquals("software callback tests must not synthesize reader events", 0, scans.get())
    }

    @Test fun dashboardDisplaysAndDismissesProtectedActivationRefusal() {
        val before = snapshot()
        var model: DashboardViewModel? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val dashboard = DashboardViewModel(
                        app.profileRepository, app.nfcTagRepository, app.localDataStore, app.enforcementEngine
                    )
                    model = dashboard
                    activity.setContent {
                        WebSnagTheme { DashboardScreen(dashboard, {}, {}, {}, {}, {}) }
                    }
                }
                val dashboard = model!!
                compose.runOnIdle { dashboard.requestQuickLock(before.activeProfile!!.copy(isActive = false)) }
                val message = "End the current focus session before starting another."
                compose.waitUntil(10_000) { dashboard.uiState.value.errorMessage == message }
                compose.onNodeWithText(message).assertIsDisplayed()
                compose.onNodeWithText("OK").performClick()
                compose.waitUntil(10_000) { dashboard.uiState.value.errorMessage == null }
                assertTrue("an activation refusal must not contaminate emergency recovery",
                    app.enforcementEngine.enforcementState.value.emergencyRecoveryError == null)
                assertProtectedSession(before)
            }
        } finally {
            instrumentation.runOnMainSync { model?.viewModelScope?.cancel() }
        }
    }

    private fun forgedIntents(): List<Intent> {
        // Keep raw input in extras (never the Intent data URI, which Android may log).
        val uri = Uri.parse("websnag://tag/${tag!!.id}")
        val uidBytes = rawUid.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ndef = NdefMessage(arrayOf(NdefRecord.createUri(uri)))
        return listOf(
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            Intent.ACTION_VIEW,
            Intent.ACTION_MAIN
        ).mapIndexed { index, action ->
            Intent(app, MainActivity::class.java).apply {
                this.action = action
                if (action == Intent.ACTION_VIEW || action == NfcAdapter.ACTION_NDEF_DISCOVERED) data = uri
                putExtra(DELIVERY_MARKER, index)
                putExtra(NfcAdapter.EXTRA_ID, uidBytes)
                putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf<Parcelable>(ndef))
                // A malformed parcelable extra must be ignored too, rather than trusted or cast.
                putExtra(NfcAdapter.EXTRA_TAG, "not a hardware Tag")
                putExtra("tagUid", rawUid)
                putExtra("customPayload", tag!!.id)
                putExtra("profileId", profileId)
                putExtra("isEnrolled", true)
                putExtra("unlocked", true)
            }
        }
    }

    private fun assertLauncherExported() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertTrue(info.exported)
    }

    private fun assertIgnored(before: EnforcementSnapshot) {
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
        assertEquals("external intents must not publish reader scans", 0, scans.get())
        assertProtectedSession(before)
        compose.onNodeWithText("FOCUS ACTIVE").assertIsDisplayed()
    }

    private fun snapshot(): EnforcementSnapshot = runBlocking {
        withTimeout(10_000) { app.profileRepository.readEnforcementSnapshot() }
    }

    private fun assertProtectedSession(before: EnforcementSnapshot) {
        assertTrue("persisted session and recovery must be unchanged", snapshot() == before)
        val state = app.enforcementEngine.enforcementState.value
        assertTrue(state.isBlockingActive)
        assertTrue(state.activeProfile == before.activeProfile)
        assertTrue(state.emergencyRecovery == before.recovery)
        assertTrue(app.enforcementEngine.isPackageBlocked("invalid.synthetic.blocked"))
        runBlocking {
            withTimeout(10_000) {
                assertTrue("rejected input must not record a tag tap",
                    app.nfcTagRepository.getTags().singleOrNull { it.id == tag!!.id } == tag)
            }
        }
    }

    private fun visibleText(): List<String> = compose.onAllNodes(
        SemanticsMatcher("all semantic nodes") { true }, useUnmergedTree = true
    ).fetchSemanticsNodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    private companion object {
        const val DELIVERY_MARKER = "websnag.test.synthetic_delivery"
    }
}
