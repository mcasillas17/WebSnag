package org.websnag.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.websnag.core.data.NfcTagRepository
import org.websnag.core.data.ProfileRepository
import org.websnag.core.enforcement.EnforcementEngine
import org.websnag.core.model.EnforcementState
import org.websnag.core.model.NfcTagRecord
import org.websnag.core.model.Profile
import org.websnag.core.model.UnlockCondition
import org.websnag.service.WebSnagAccessibilityService

data class DashboardUiState(
    val nfcUnlockPromptProfile: Profile? = null,
    val errorMessage: String? = null
)

class DashboardViewModel(
    private val profileRepository: ProfileRepository,
    private val nfcTagRepository: NfcTagRepository,
    private val enforcementEngine: EnforcementEngine
) : ViewModel() {

    val enforcementState: StateFlow<EnforcementState> = enforcementEngine.enforcementState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EnforcementState())

    val profiles: StateFlow<List<Profile>> = profileRepository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<NfcTagRecord>> = nfcTagRepository.tagsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun isAccessibilityServiceRunning(): Boolean {
        return WebSnagAccessibilityService.isServiceRunning
    }

    fun onProfileToggleClicked(profile: Profile) {
        viewModelScope.launch {
            if (profile.isActive) {
                // Profile is active -> check if it requires NFC tag to deactivate
                when (profile.unlockCondition) {
                    is UnlockCondition.RequireNfcTag -> {
                        _uiState.value = _uiState.value.copy(nfcUnlockPromptProfile = profile)
                    }
                    is UnlockCondition.DurationExpiry -> {
                        if (profile.unlockCondition.requiredTagUid != null) {
                            _uiState.value = _uiState.value.copy(nfcUnlockPromptProfile = profile)
                        } else {
                            enforcementEngine.deactivateProfile(profile.id)
                        }
                    }
                    UnlockCondition.ManualOnly -> {
                        enforcementEngine.deactivateProfile(profile.id)
                    }
                }
            } else {
                // Activate profile
                enforcementEngine.activateProfile(profile.id)
            }
        }
    }

    fun quickLockProfile(profile: Profile) {
        viewModelScope.launch {
            enforcementEngine.activateProfile(profile.id)
        }
    }

    fun dismissNfcPrompt() {
        _uiState.value = _uiState.value.copy(nfcUnlockPromptProfile = null)
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
