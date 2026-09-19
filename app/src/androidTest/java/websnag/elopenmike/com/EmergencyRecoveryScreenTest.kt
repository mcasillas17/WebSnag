package websnag.elopenmike.com

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import websnag.elopenmike.com.core.model.EmergencyRecovery
import websnag.elopenmike.com.core.model.EnforcementState
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.core.data.DefaultNfcTagRepository
import websnag.elopenmike.com.core.data.AndroidKeystoreTagIdentityProtector
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.DefaultProfileRepository
import websnag.elopenmike.com.core.data.MigrationStoreHarness
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.ui.overlay.BlockOverlayScreen
import websnag.elopenmike.com.ui.profiles.ProfileEditorScreen
import websnag.elopenmike.com.ui.profiles.ProfilesViewModel
import websnag.elopenmike.com.ui.theme.WebSnagTheme
import websnag.elopenmike.com.ui.dashboard.DashboardScreen
import websnag.elopenmike.com.ui.dashboard.DashboardViewModel
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class EmergencyRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun profileSaveRaceShowsAnErrorAndKeepsTheEditorOpen() {
        val harness = MigrationStoreHarness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var model: ProfilesViewModel? = null
        var engine: EnforcementEngine? = null
        var navigatedBack = false
        val finishSave = CompletableDeferred<Unit>()
        try {
            runBlocking { harness.open() }
            val backing = DefaultProfileRepository(harness.local)
            runBlocking {
                backing.saveProfile(Profile("synthetic-editor", "Original", linkedTagId = "synthetic-tag"))
            }
            val racing = object : ProfileRepository by backing {
                override suspend fun saveProfile(profile: Profile) {
                    finishSave.await()
                    backing.setActiveProfile(profile.id)
                    backing.saveProfile(profile)
                }
            }
            val enforcement = EnforcementEngine(backing, harness.local, scope)
            engine = enforcement
            compose.runOnIdle {
                model = ProfilesViewModel(racing, DefaultNfcTagRepository(harness.local), { emptyList() }, enforcement)
            }
            val editor = model!!
            compose.setContent {
                WebSnagTheme {
                    ProfileEditorScreen("synthetic-editor", editor, onNavigateBack = { navigatedBack = true })
                }
            }
            compose.waitUntil(10_000) { editor.editorState.value.profileId == "synthetic-editor" }
            compose.onNodeWithText("Profile Name").performTextReplacement("Keep my edit")
            compose.onNodeWithText("Save").performClick()
            val errorMessage = "End the active session before changing this profile."
            compose.onNodeWithText("Save").assertIsNotEnabled()
            compose.onNodeWithText(errorMessage).assertDoesNotExist()
            finishSave.complete(Unit)
            compose.waitUntil(10_000) { compose.onNodeWithText(errorMessage).isDisplayed() }
            compose.onNodeWithText(errorMessage).assertIsDisplayed()
            compose.onNodeWithText("Keep my edit").assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(navigatedBack)
                assertFalse(editor.editorState.value.isSaving)
                assertFalse(editor.editorState.value.isSaved)
            }
        } finally {
            compose.runOnIdle { model?.viewModelScope?.cancel() }
            engine?.stop()
            runBlocking {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                harness.close()
            }
        }
    }

    @Test fun dashboardStorageFailureReplacesEnrollmentWarningWithVisibleError() {
        val harness = MigrationStoreHarness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val alias = "synthetic.websnag.dashboard.${UUID.randomUUID()}"
        val failWrites = AtomicBoolean(false)
        var model: DashboardViewModel? = null
        var engine: EnforcementEngine? = null
        try {
            runBlocking { harness.open() }
            val store = object : DataStore<Preferences> by harness.store {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    if (failWrites.get()) throw IOException("Synthetic write failure")
                    return harness.store.updateData(transform)
                }
            }
            val local = LocalDataStore(store)
            val profiles = DefaultProfileRepository(local)
            val tags = DefaultNfcTagRepository(local, AndroidKeystoreTagIdentityProtector(alias))
            val profile = Profile("synthetic-dashboard", "Synthetic dashboard")
            runBlocking { profiles.saveProfile(profile) }
            val enforcement = EnforcementEngine(profiles, local, scope, nfcTagRepository = tags,
                hasEnrolledNfcTag = { tags.getTags().isNotEmpty() })
            engine = enforcement
            compose.runOnIdle { model = DashboardViewModel(profiles, tags, local, enforcement) }
            val dashboard = model!!
            compose.setContent { WebSnagTheme { DashboardScreen(dashboard, {}, {}, {}, {}, {}) } }
            compose.runOnIdle { dashboard.requestQuickLock(profile) }
            compose.waitUntil(10_000) { dashboard.uiState.value.showNoNfcEnrolledWarning }
            compose.onNodeWithText("No NFC Tags Enrolled").assertIsDisplayed()
            runBlocking { checkNotNull(tags.enrollTag("04A1B2C3D4E510", "Synthetic dashboard tag", null, "")) }
            failWrites.set(true)
            compose.runOnIdle { dashboard.requestQuickLock(profile) }
            compose.waitUntil(10_000) { dashboard.uiState.value.errorMessage != null }
            compose.onNodeWithText("Saved data could not be updated. The request was not completed.").assertIsDisplayed()
            compose.onNodeWithText("No NFC Tags Enrolled").assertDoesNotExist()
            assertFalse(enforcement.enforcementState.value.isBlockingActive)
            compose.onNodeWithText("OK").performClick()
            compose.waitUntil(10_000) { dashboard.uiState.value.errorMessage == null }
            failWrites.set(false)
            runBlocking { tags.deleteTag(tags.getTags().single().id) }
            compose.runOnIdle { dashboard.requestQuickLock(profile) }
            compose.waitUntil(10_000) { dashboard.uiState.value.showNoNfcEnrolledWarning }
            compose.onNodeWithText("No NFC Tags Enrolled").assertIsDisplayed()
            compose.onNodeWithText("Saved data could not be updated. The request was not completed.").assertDoesNotExist()
        } finally {
            compose.runOnIdle { model?.viewModelScope?.cancel() }
            engine?.stop()
            runBlocking {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                harness.close()
            }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    private fun state(minutes: Int = 17, phrase: Boolean = false, enabled: Boolean = true) =
        EnforcementState(isBlockingActive = true, activeProfile = Profile(
            "synthetic", "Synthetic", isActive = true, sessionId = "session-a",
            unlockCondition = UnlockCondition.RequireNfcTag(
                emergencyCooldownMinutes = minutes, requireIntentionPhrase = phrase, allowEmergencyUnlock = enabled
            )
        ))

    @Test fun configuredOptionalPhraseStartsWithoutConfirmation() {
        var confirmed: Boolean? = null
        compose.setContent { WebSnagTheme { BlockOverlayScreen("synthetic", state(), {}, { confirmed = it }, {}) } }
        compose.onNodeWithText("Emergency Recovery (Intentional Friction)").performClick()
        compose.onNodeWithText("Start 17 Min Cooldown").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(false, confirmed) }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun requiredPhraseAndDisabledRecoveryRemainGated() {
        val state = mutableStateOf(state(minutes = 1, phrase = true))
        compose.setContent { WebSnagTheme { BlockOverlayScreen("synthetic", state.value, {}, {}, {}) } }
        compose.onNodeWithText("Emergency Recovery (Intentional Friction)").performClick()
        compose.onNodeWithText("Start 1 Min Cooldown").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("I choose to pause my focus")
        compose.onNodeWithText("Start 1 Min Cooldown").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(activeProfile = state.value.activeProfile!!.copy(
            sessionId = "session-b", unlockCondition = UnlockCondition.RequireNfcTag(allowEmergencyUnlock = false)
        )) }
        compose.onNodeWithText("Emergency Recovery (Intentional Friction)").assertDoesNotExist()
        compose.onNodeWithText("Start 1 Min Cooldown").assertDoesNotExist()
    }

    @Test fun authoritativeCountdownAndReplacementResetPhrase() {
        val state = mutableStateOf(state(phrase = true))
        compose.setContent { WebSnagTheme { BlockOverlayScreen("synthetic", state.value, {}, {}, {}) } }
        compose.onNodeWithText("Emergency Recovery (Intentional Friction)").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("I choose to pause my focus")
        val request = EmergencyRecovery("synthetic", Long.MIN_VALUE, 17 * 60_000L, true, "request-a", "session-a", 0, "boot")
        compose.runOnIdle { state.value = state.value.copy(
            emergencyRecovery = request, emergencyCooldownActive = true, remainingEmergencyMs = 61_000
        ) }
        compose.onNodeWithText("01:01").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(
            emergencyRecovery = request.copy(requestId = "request-b"), remainingEmergencyMs = 17 * 60_000L
        ) }
        compose.onNodeWithText("17:00").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(
            emergencyRecovery = null, emergencyCooldownActive = false, remainingEmergencyMs = 0
        ) }
        compose.onNodeWithText("Start 17 Min Cooldown").assertIsNotEnabled()
    }
}
