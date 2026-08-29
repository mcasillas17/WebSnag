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
import java.util.Calendar

data class DashboardUiState(
    val nfcUnlockPromptProfile: Profile? = null,
    val showNoNfcEnrolledWarning: Boolean = false,
    val errorMessage: String? = null
)

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
        val calendar = Calendar.getInstance()
        val todayYear = calendar.get(Calendar.YEAR)
        val todayDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

        var todaySeconds = 0L
        sessions.forEach { s ->
            val sCal = Calendar.getInstance().apply { timeInMillis = s.startTimeEpochMs }
            if (sCal.get(Calendar.YEAR) == todayYear && sCal.get(Calendar.DAY_OF_YEAR) == todayDayOfYear) {
                todaySeconds += s.durationSeconds
            }
        }
        if (enforcementState.sessionStartedAtEpochMs != null) {
            todaySeconds += maxOf(0L, (System.currentTimeMillis() - enforcementState.sessionStartedAtEpochMs) / 1000L)
        }
        (todaySeconds / 60).toInt()
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
                quickLockProfile(profile, tags.value)
            }
        }
    }

    fun quickLockProfile(profile: Profile, currentTags: List<NfcTagRecord>) {
        val requiresNfc = profile.unlockCondition is UnlockCondition.RequireNfcTag ||
                (profile.unlockCondition is UnlockCondition.DurationExpiry && profile.unlockCondition.requiredTagId != null)

        if (requiresNfc && currentTags.isEmpty()) {
            _uiState.value = _uiState.value.copy(showNoNfcEnrolledWarning = true)
            return
        }

        viewModelScope.launch {
            enforcementEngine.activateProfile(profile.id)
        }
    }

    fun emergencyUnlockActiveProfile() {
        val active = enforcementState.value.activeProfile ?: return
        enforcementEngine.startEmergencyUnlock(intentionConfirmed = true) {}
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
