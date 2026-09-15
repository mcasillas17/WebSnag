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
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.data.MigrationStoreHarness
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.ui.overlay.BlockOverlayActivity
import websnag.elopenmike.com.ui.profiles.ProfilesViewModel

/** Actual Activities, app engine and app DataStore. Inputs are synthetic and no NFC hardware is needed. */
class EmergencyRecoveryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<WebSnagApp>()
    private val profileId = "synthetic-emergency-activity"
    private val fixtureJson = Json { ignoreUnknownKeys = true }

    @Before fun setup(): Unit = runBlocking {
        withTimeout(10_000) { app.profileRepository.profilesFlow.first { it.isNotEmpty() } }
        app.enforcementEngine.enforcementState.value.activeProfile?.let {
            check(app.enforcementEngine.requestEnd(it.id, EndRequest.ScheduleEnded))
        }
        app.profileRepository.saveProfile(Profile(profileId, "Synthetic recovery", linkedTagId = "synthetic-tag",
            blockedPackages = emptySet(), unlockCondition = UnlockCondition.RequireNfcTag(
                requiredTagId = "synthetic-tag", emergencyCooldownMinutes = 17, requireIntentionPhrase = false)))
        app.profileRepository.setActiveProfile(profileId)
        withTimeout(10_000) { app.enforcementEngine.enforcementState.first { it.activeProfile?.id == profileId } }
    }

    @After fun cleanup() = runBlocking {
        if (app.enforcementEngine.enforcementState.value.activeProfile?.id == profileId) {
            assertTrue(app.enforcementEngine.requestEnd(profileId, EndRequest.ScheduleEnded))
        }
        app.profileRepository.deleteProfile(profileId)
    }

    @Test fun dashboardLostTagRecoveryIsReachableWithEmptyBlocklist() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Emergency Unlock").performClick()
            compose.onNodeWithText("Start 17 Min Cooldown").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { app.enforcementEngine.enforcementState.value.emergencyCooldownActive }
            val request = runBlocking { app.localDataStore.emergencyRecoveryFlow.first()!! }
            assertFalse(request.intentionConfirmed)
            assertEquals(1_020_000L, request.durationMs)
            assertTrue(app.enforcementEngine.enforcementState.value.isBlockingActive)
        }
    }

    @Test fun overlayActivityRecreationKeepsPersistedRecoveryWithoutRestarting() {
        val intent = Intent(app, BlockOverlayActivity::class.java)
            .putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, "invalid.synthetic.blocked")
        ActivityScenario.launch<BlockOverlayActivity>(intent).use { scenario ->
            compose.onNodeWithText("Emergency Recovery (Intentional Friction)").performClick()
            compose.onNodeWithText("Start 17 Min Cooldown").performClick()
            compose.waitUntil(10_000) { app.enforcementEngine.enforcementState.value.emergencyCooldownActive }
            val before = runBlocking { app.localDataStore.emergencyRecoveryFlow.first()!! }
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
            assertEquals("synthetic-tag", condition.requiredTagId)
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
}
