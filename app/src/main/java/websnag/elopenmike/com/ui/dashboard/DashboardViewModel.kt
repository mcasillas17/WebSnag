package websnag.elopenmike.com.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.activity.focusIntervals
import websnag.elopenmike.com.core.activity.focusPerWindow
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.NfcTagRepository
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.model.EnforcementState
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.service.WebSnagAccessibilityService
import java.time.LocalDate
import java.time.ZoneId

data class DashboardUiState(
    val nfcUnlockPromptProfile: Profile? = null,
    val emergencyUnlockProfile: Profile? = null,
    val emergencySessionUiKey: String? = null,
    val showNoNfcEnrolledWarning: Boolean = false,
    val errorMessage: String? = null
) {
    fun showsEmergencyDialog(state: EnforcementState): Boolean =
        emergencyUnlockProfile != null &&
            emergencySessionUiKey == state.sessionUiKey &&
            emergencyUnlockProfile.id == state.activeProfile?.id &&
            state.isBlockingActive
}

class DashboardViewModel(
    private val profileRepository: ProfileRepository,
    private val nfcTagRepository: NfcTagRepository,
    private val localDataStore: LocalDataStore,
    private val enforcementEngine: EnforcementEngine
) : ViewModel() {

    val enforcementState: StateFlow<EnforcementState> = enforcementEngine.enforcementState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EnforcementState())

    val profiles: StateFlow<List<Profile>> = profileRepository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<NfcTagRecord>> = nfcTagRepository.tagsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayFocusMinutes: StateFlow<Int> = combine(
        localDataStore.focusSessionsFlow,
        enforcementEngine.enforcementState
    ) { sessions, enforcementState ->
        // Same local-day allocation as the Activity charts, so both screens agree on "today".
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val intervals = focusIntervals(sessions, enforcementState.sessionStartedAtEpochMs, System.currentTimeMillis())
        (focusPerWindow(intervals, listOf(today, today.plusDays(1)), zone).single() / 60_000L).toInt()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _selectedProfileId = MutableStateFlow<String?>(null)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    fun selectProfile(profileId: String) {
        _selectedProfileId.value = profileId
    }

    fun isAccessibilityServiceRunning(): Boolean {
        return WebSnagAccessibilityService.isServiceRunning
    }

    fun onProfileToggleClicked(profile: Profile) {
        viewModelScope.launch {
            if (profile.isActive) {
                when (profile.unlockCondition) {
                    is UnlockCondition.RequireNfcTag -> {
                        _uiState.value = _uiState.value.copy(nfcUnlockPromptProfile = profile)
                    }
                    is UnlockCondition.DurationExpiry -> {
                        if (profile.unlockCondition.requiredTagId != null) {
                            _uiState.value = _uiState.value.copy(nfcUnlockPromptProfile = profile)
                        } else {
                            enforcementEngine.requestEnd(profile.id, EndRequest.Manual)
                        }
                    }
                    UnlockCondition.ManualOnly -> {
                        enforcementEngine.requestEnd(profile.id, EndRequest.Manual)
                    }
                }
            } else {
                requestQuickLock(profile)
            }
        }
    }

    fun requestQuickLock(profile: Profile) {
        viewModelScope.launch {
            if (!enforcementEngine.tryActivateProfile(profile.id)) {
                _uiState.value = _uiState.value.copy(showNoNfcEnrolledWarning = true)
            }
        }
    }

    fun emergencyUnlockActiveProfile() {
        val state = enforcementEngine.enforcementState.value
        val active = state.activeProfile ?: return
        val condition = active.unlockCondition as? UnlockCondition.RequireNfcTag
        if (condition?.allowEmergencyUnlock == true && !state.storageRecoveryRequired) {
            _uiState.value = _uiState.value.copy(
                emergencyUnlockProfile = active, emergencySessionUiKey = state.sessionUiKey, nfcUnlockPromptProfile = null
            )
        } else {
            onProfileToggleClicked(active)
        }
    }

    fun dismissEmergencyDialog() {
        _uiState.value = _uiState.value.copy(emergencyUnlockProfile = null, emergencySessionUiKey = null)
    }

    fun startEmergencyUnlock(confirmed: Boolean, state: EnforcementState) {
        viewModelScope.launch {
            enforcementEngine.startEmergencyUnlock(confirmed, state.activeProfile?.sessionId, state.emergencyRecovery?.requestId)
        }
    }

    fun cancelEmergencyUnlock(state: EnforcementState) {
        state.emergencyRecovery?.requestId?.let { request ->
            viewModelScope.launch { enforcementEngine.cancelEmergencyUnlock(request) }
        }
    }

    fun dismissNfcPrompt() {
        _uiState.value = _uiState.value.copy(nfcUnlockPromptProfile = null)
    }

    fun dismissNoNfcWarning() {
        _uiState.value = _uiState.value.copy(showNoNfcEnrolledWarning = false)
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
