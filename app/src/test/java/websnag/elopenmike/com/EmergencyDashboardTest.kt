package websnag.elopenmike.com

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.enforcement.EmergencyClock
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.ui.dashboard.DashboardViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyDashboardTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyDialogSurvivesBindingButNotSameProfileReactivation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var viewModel: DashboardViewModel? = null
        try {
            val repository = FakeProfileRepository()
            repository.saveProfile(Profile("legacy", "Legacy", isActive = true,
                activatedAtEpochMs = 1_700_000_000_000L, sessionId = null))
            val local = LocalDataStore(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporary.newFolder().resolve("legacy-dashboard.preferences_pb")
            })
            val engine = EnforcementEngine(repository, coroutineScope = backgroundScope,
                emergencyClock = EmergencyClock({ testScheduler.currentTime }, { "boot" }),
                hasEnrolledNfcTag = { true })
            val model = DashboardViewModel(repository, FakeNfcTagRepository(), local, engine)
            viewModel = model
            backgroundScope.launch { model.enforcementState.collect {} }
            runCurrent()
            assertNull(engine.enforcementState.value.activeProfile!!.sessionId)
            model.emergencyUnlockActiveProfile()
            assertTrue(model.uiState.value.showsEmergencyDialog(engine.enforcementState.value))
            model.startEmergencyUnlock(true, engine.enforcementState.value)
            runCurrent()
            assertNotNull(engine.enforcementState.value.activeProfile!!.sessionId)
            assertTrue("binding this legacy activation must not dismiss its recovery dialog",
                model.uiState.value.showsEmergencyDialog(engine.enforcementState.value))
            engine.tryActivateProfile("legacy")
            runCurrent()
            assertEquals(1_700_000_000_000L, engine.enforcementState.value.activeProfile!!.activatedAtEpochMs)
            assertFalse("a real reactivation must reset presentation even with the same wall timestamp",
                model.uiState.value.showsEmergencyDialog(engine.enforcementState.value))
        } finally {
            viewModel?.viewModelScope?.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun lostTagRecoveryDoesNotRouteBackToTheMissingTag() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var viewModel: DashboardViewModel? = null
        try {
            val repository = FakeProfileRepository()
            repository.saveProfile(Profile("synthetic", "Synthetic", isActive = true, blockedPackages = emptySet()))
            val local = LocalDataStore(PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporary.newFolder().resolve("dashboard.preferences_pb")
            })
            val engine = EnforcementEngine(repository, coroutineScope = backgroundScope)
            val model = DashboardViewModel(repository, FakeNfcTagRepository(), local, engine)
            viewModel = model
            backgroundScope.launch { model.enforcementState.collect {} }
            runCurrent()
            model.emergencyUnlockActiveProfile()
            assertNull("lost-tag recovery must not demand the lost tag", model.uiState.value.nfcUnlockPromptProfile)
            assertEquals("synthetic", model.uiState.value.emergencyUnlockProfile?.id)
        } finally {
            viewModel?.viewModelScope?.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
