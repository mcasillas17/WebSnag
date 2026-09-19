package websnag.elopenmike.com

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.data.EnforcementSnapshot
import websnag.elopenmike.com.core.data.MigrationStoreHarness
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.ui.dashboard.DashboardViewModel
import websnag.elopenmike.com.ui.overlay.BlockOverlayActivity
import websnag.elopenmike.com.ui.profiles.ProfilesViewModel

/** Actual Activities, app engine and app DataStore. Inputs are synthetic and no NFC hardware is needed. */
class EmergencyRecoveryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<WebSnagApp>()
    private val profileId = "synthetic-emergency-activity-${UUID.randomUUID()}"
    private var enrolledTagId: String? = null
    private val fixtureJson = Json { ignoreUnknownKeys = true }

    @Before fun setup(): Unit = runBlocking {
        withTimeout(10_000) {
            app.profileRepository.profilesFlow.first { it.isNotEmpty() }
            check(app.profileRepository.readEnforcementSnapshot().activeProfile == null) {
                "Activity tests require a disposable installation with no active session."
            }
            val rawUid = "04A1B2C3D4E501"
            check(app.nfcTagRepository.getTagForUid(rawUid) == null)
            val tag = checkNotNull(app.nfcTagRepository.enrollTag(rawUid, "Synthetic recovery tag", null, ""))
            enrolledTagId = tag.id
            app.profileRepository.saveProfile(Profile(profileId, "Synthetic recovery", linkedTagId = tag.id,
                blockedPackages = emptySet(), unlockCondition = UnlockCondition.RequireNfcTag(
                    requiredTagId = tag.id, emergencyCooldownMinutes = 17, requireIntentionPhrase = false)))
            check(app.enforcementEngine.tryActivateProfile(profileId))
            app.enforcementEngine.enforcementState.first { it.activeProfile?.id == profileId }
        }
    }

    @After fun cleanup(): Unit = runBlocking {
        withTimeout(10_000) {
            if (app.enforcementEngine.enforcementState.value.activeProfile?.id == profileId) {
                assertTrue(app.enforcementEngine.requestEnd(profileId, EndRequest.ScheduleEnded))
            }
            app.profileRepository.deleteProfile(profileId)
            enrolledTagId?.let { app.nfcTagRepository.deleteTag(it) }
        }
    }

    @Test fun dashboardLostTagRecoveryIsReachableWithEmptyBlocklist() {
        // The physical tag is unavailable, but its enrollment remains. Recovery needs no new tap.
        val before = snapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Emergency Unlock").performClick()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithText("Start 17 Min Cooldown").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { app.enforcementEngine.enforcementState.value.emergencyCooldownActive }
            val request = runBlocking { app.localDataStore.emergencyRecoveryFlow.first()!! }
            assertFalse(request.intentionConfirmed)
            assertEquals(1_020_000L, request.durationMs)
            assertTrue(app.enforcementEngine.enforcementState.value.isBlockingActive)
            assertTrue(request.sessionId == before.activeProfile?.sessionId)
            compose.onNodeWithText("Cancel Timer").performClick()
            compose.waitUntil(10_000) { app.enforcementEngine.enforcementState.value.emergencyRecovery == null }
            assertSessionUnchanged(before)
        }
    }

    @Test fun dashboardWithDisabledEmergencyRequiresNfcAndKeepsSession() {
        configureRecovery(enabled = false)
        val before = snapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Emergency Unlock").assertDoesNotExist()
            compose.onNodeWithText("Unlock Profile").performClick()
            compose.onNodeWithText("Physical NFC Tag Required").assertIsDisplayed()
            compose.onNodeWithText("Start 17 Min Cooldown").assertDoesNotExist()
            compose.onNodeWithText("Understood").performClick()
            compose.onNodeWithText("Physical NFC Tag Required").assertDoesNotExist()
            assertSessionUnchanged(before)
        }
    }

    @Test fun overlayWithDisabledEmergencyOffersNoRecoveryAndKeepsSession() {
        configureRecovery(enabled = false)
        val before = snapshot()
        ActivityScenario.launch<BlockOverlayActivity>(overlayIntent()).use {
            compose.onNodeWithText("Tap physical NFC tag to unlock").assertIsDisplayed()
            compose.onNodeWithText("Emergency Recovery (Intentional Friction)").assertDoesNotExist()
            compose.onNodeWithText("Start 17 Min Cooldown").assertDoesNotExist()
            compose.onNodeWithText("Return to Home Screen").assertIsDisplayed()
            assertSessionUnchanged(before)
        }
    }

    @Test fun dashboardRequiredPhrasePersistsOnlyAfterConfirmation() {
        assertRequiredPhraseGate(dashboard = true)
    }

    @Test fun overlayRequiredPhrasePersistsOnlyAfterConfirmation() {
        assertRequiredPhraseGate(dashboard = false)
    }

    @Test fun manualDashboardCallbackCannotEndPersistedNfcSession() = runBlocking {
        val before = snapshot()
        var model: DashboardViewModel? = null
        try {
            withContext(Dispatchers.Main) {
                val dashboard = DashboardViewModel(
                    app.profileRepository, app.nfcTagRepository, app.localDataStore, app.enforcementEngine
                )
                model = dashboard
                val scopeJob = dashboard.viewModelScope.coroutineContext[Job]!!
                val sharingJobs = scopeJob.children.toSet()
                // A stale caller's manual policy is not authority over the persisted NFC policy.
                dashboard.onProfileToggleClicked(before.activeProfile!!.copy(unlockCondition = UnlockCondition.ManualOnly))
                withTimeout(10_000) {
                    // Await the callback's command, not the model's long-lived stateIn collectors.
                    scopeJob.children.filterNot { it in sharingJobs }.toList().joinAll()
                }
            }
            assertSessionUnchanged(before)
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
        }
    }

    @Test fun overlayActivityRecreationKeepsPersistedRecoveryWithoutRestarting() {
        ActivityScenario.launch<BlockOverlayActivity>(overlayIntent()).use { scenario ->
            compose.onNodeWithText("Emergency Recovery (Intentional Friction)").performClick()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithText("Start 17 Min Cooldown").performClick()
            compose.waitUntil(10_000) { app.enforcementEngine.enforcementState.value.emergencyCooldownActive }
            val before = runBlocking { app.localDataStore.emergencyRecoveryFlow.first()!! }
            assertFalse(before.intentionConfirmed)
            assertEquals(1_020_000L, before.durationMs)
            scenario.recreate()
            compose.onNodeWithText("Emergency Recovery (Intentional Friction)").performClick()
            compose.onNodeWithText("Cancel Timer").assertIsDisplayed()
            assertEquals(before, runBlocking { app.localDataStore.emergencyRecoveryFlow.first() })
            compose.onNodeWithText("Cancel Timer").performClick()
            compose.waitUntil(10_000) { app.enforcementEngine.enforcementState.value.emergencyRecovery == null }
            assertTrue(app.enforcementEngine.enforcementState.value.isBlockingActive)
            assertNull(runBlocking { app.localDataStore.emergencyRecoveryFlow.first() })
        }
    }

    @Test fun editingAnOptionalPhraseProfilePreservesItsPolicy() = runBlocking {
        assertTrue(app.enforcementEngine.requestEnd(profileId, EndRequest.ScheduleEnded))
        var viewModel: ProfilesViewModel? = null
        try {
            withContext(Dispatchers.Main) {
                viewModel = ProfilesViewModel(app.profileRepository, app.nfcTagRepository, app.installedAppsRepository, app.enforcementEngine)
                viewModel.loadProfileForEditing(profileId)
            }
            val model = viewModel!!
            withTimeout(10_000) { model.editorState.first { it.profileId == profileId } }
            withContext(Dispatchers.Main) {
                model.onNameChanged("Synthetic renamed")
                model.saveProfile()
            }
            withTimeout(10_000) { model.editorState.first { it.isSaved } }
            val condition = app.profileRepository.getProfileById(profileId)!!.unlockCondition as UnlockCondition.RequireNfcTag
            assertFalse(condition.requireIntentionPhrase)
            assertEquals(17, condition.emergencyCooldownMinutes)
            assertTrue(condition.requiredTagId == enrolledTagId)
        } finally { withContext(Dispatchers.Main) { viewModel?.viewModelScope?.cancel() } }
    }

    @Test fun legacyDialogsKeepCountdownVisibleWhenTheSessionUuidIsBound() {
        val fixture = MigrationStoreHarness()
        val legacy = runBlocking {
            try {
                val raw = fixture.load("alpha2-current")[stringPreferencesKey("profiles_json")]!!
                fixtureJson.decodeFromString<List<Profile>>(raw)
                    .single { it.isActive }.copy(id = profileId)
            } finally { fixture.close() }
        }
        assertNull("the released active row must not already have a session UUID", legacy.sessionId)
        for (dashboard in listOf(true, false)) {
            runBlocking {
                assertTrue(app.enforcementEngine.requestEnd(profileId, EndRequest.ScheduleEnded))
                // Load the released active row, not setActiveProfile (which would mint a UUID).
                app.localDataStore.saveProfiles(app.profileRepository.getProfiles().filterNot { it.id == profileId } + legacy)
                app.localDataStore.setActiveProfileId(profileId)
                withTimeout(10_000) {
                    app.enforcementEngine.enforcementState.first {
                        it.activeProfile?.id == profileId && it.activeProfile.sessionId == null
                    }
                }
            }
            val intent = Intent(app, if (dashboard) MainActivity::class.java else BlockOverlayActivity::class.java)
                .putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, "invalid.synthetic.blocked")
            ActivityScenario.launch<ComponentActivity>(intent).use {
                compose.onNodeWithText(if (dashboard) "Emergency Unlock" else "Emergency Recovery (Intentional Friction)").performClick()
                compose.onNode(hasSetTextAction()).performTextInput("I choose to pause my focus")
                compose.onNodeWithText("Start 17 Min Cooldown").performClick()
                compose.waitUntil(10_000) {
                    app.enforcementEngine.enforcementState.value.emergencyCooldownActive &&
                        app.enforcementEngine.enforcementState.value.activeProfile?.sessionId != null
                }
                // No second open/click: the same dialog must survive this in-place legacy bind.
                compose.onNodeWithText("Cancel Timer").assertIsDisplayed()
            }
        }
    }

    private fun overlayIntent() = Intent(app, BlockOverlayActivity::class.java)
        .putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, "invalid.synthetic.blocked")

    private fun snapshot(): EnforcementSnapshot = runBlocking {
        withTimeout(10_000) { app.profileRepository.readEnforcementSnapshot() }
    }

    private fun assertSessionUnchanged(before: EnforcementSnapshot) {
        assertTrue("persisted session and recovery must be unchanged", snapshot() == before)
        val state = app.enforcementEngine.enforcementState.value
        assertTrue(state.isBlockingActive)
        assertTrue(state.activeProfile == before.activeProfile)
        assertTrue(state.emergencyRecovery == before.recovery)
    }

    private fun configureRecovery(enabled: Boolean = true, phraseRequired: Boolean = false) = runBlocking {
        withTimeout(10_000) {
            assertTrue(app.enforcementEngine.requestEnd(profileId, EndRequest.ScheduleEnded))
            val profile = app.profileRepository.getProfileById(profileId)!!
            val condition = profile.unlockCondition as UnlockCondition.RequireNfcTag
            app.profileRepository.saveProfile(profile.copy(unlockCondition = condition.copy(
                allowEmergencyUnlock = enabled, requireIntentionPhrase = phraseRequired
            )))
            assertTrue(app.enforcementEngine.tryActivateProfile(profileId))
            app.enforcementEngine.enforcementState.first { it.activeProfile?.id == profileId }
        }
    }

    private fun assertRequiredPhraseGate(dashboard: Boolean) {
        configureRecovery(phraseRequired = true)
        val before = snapshot()
        val intent = if (dashboard) Intent(app, MainActivity::class.java) else overlayIntent()
        ActivityScenario.launch<ComponentActivity>(intent).use {
            compose.onNodeWithText(if (dashboard) "Emergency Unlock" else "Emergency Recovery (Intentional Friction)")
                .performClick()
            compose.onNodeWithText("Start 17 Min Cooldown").assertIsNotEnabled().performClick()
            assertSessionUnchanged(before)
            compose.onNode(hasSetTextAction()).performTextInput("not the required phrase")
            compose.onNodeWithText("Start 17 Min Cooldown").assertIsNotEnabled().performClick()
            assertSessionUnchanged(before)
            compose.onNode(hasSetTextAction()).performTextReplacement("I choose to pause my focus")
            compose.onNodeWithText("Start 17 Min Cooldown").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { app.enforcementEngine.enforcementState.value.emergencyCooldownActive }
            val persisted = snapshot()
            val request = persisted.recovery!!
            assertTrue(request.intentionConfirmed)
            assertEquals(1_020_000L, request.durationMs)
            assertTrue(request.sessionId == before.activeProfile?.sessionId)
            assertTrue(persisted.activeProfile == before.activeProfile)
            assertTrue(app.enforcementEngine.enforcementState.value.isBlockingActive)
            compose.onNodeWithText("Cancel Timer").performClick()
            compose.waitUntil(10_000) { app.enforcementEngine.enforcementState.value.emergencyRecovery == null }
            assertSessionUnchanged(before)
        }
    }
}
