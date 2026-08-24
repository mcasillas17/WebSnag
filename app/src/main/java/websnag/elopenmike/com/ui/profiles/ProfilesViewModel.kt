package websnag.elopenmike.com.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.data.InstalledAppsRepository
import websnag.elopenmike.com.core.data.NfcTagRepository
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.AppCategory
import websnag.elopenmike.com.core.model.AppInfo
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import java.util.UUID

data class ProfileEditorUiState(
    val profileId: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val colorHex: String = "#2563EB",
    val filterMode: FilterMode = FilterMode.BLOCKLIST,
    val selectedPackages: Set<String> = emptySet(),
    val linkedTagUid: String? = null,
    val emergencyCooldownMinutes: Int = 5,
    val allowEmergencyUnlock: Boolean = true,
    val requireTagToUnlock: Boolean = true,
    val searchQuery: String = "",
    val selectedCategory: AppCategory? = null,
    val isLoadingApps: Boolean = true,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false
)

class ProfilesViewModel(
    private val profileRepository: ProfileRepository,
    private val nfcTagRepository: NfcTagRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val enforcementEngine: EnforcementEngine
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> = profileRepository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<NfcTagRecord>> = nfcTagRepository.tagsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _editorState = MutableStateFlow(ProfileEditorUiState())
    val editorState: StateFlow<ProfileEditorUiState> = _editorState.asStateFlow()

    init {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            _editorState.value = _editorState.value.copy(isLoadingApps = true)
            val apps = installedAppsRepository.getInstalledApps()
            _installedApps.value = apps
            _editorState.value = _editorState.value.copy(isLoadingApps = false)
        }
    }

    fun loadProfileForEditing(profileId: String?) {
        viewModelScope.launch {
            if (profileId == null || profileId == "new") {
                _editorState.value = ProfileEditorUiState(
                    profileId = UUID.randomUUID().toString(),
                    isLoadingApps = _installedApps.value.isEmpty()
                )
            } else {
                val profile = profileRepository.getProfileById(profileId)
                if (profile != null) {
                    val isTagRequired = profile.unlockCondition is UnlockCondition.RequireNfcTag
                    val emergencyCooldown = when (val c = profile.unlockCondition) {
                        is UnlockCondition.RequireNfcTag -> c.emergencyCooldownMinutes
                        else -> 5
                    }
                    val allowEmergency = when (val c = profile.unlockCondition) {
                        is UnlockCondition.RequireNfcTag -> c.allowEmergencyUnlock
                        else -> true
                    }

                    _editorState.value = ProfileEditorUiState(
                        profileId = profile.id,
                        name = profile.name,
                        description = profile.description,
                        colorHex = profile.colorHex,
                        filterMode = profile.filterMode,
                        selectedPackages = profile.blockedPackages,
                        linkedTagUid = profile.linkedTagUid,
                        emergencyCooldownMinutes = emergencyCooldown,
                        allowEmergencyUnlock = allowEmergency,
                        requireTagToUnlock = isTagRequired,
                        isLoadingApps = _installedApps.value.isEmpty()
                    )
                }
            }
        }
    }

    fun onNameChanged(name: String) {
        _editorState.value = _editorState.value.copy(name = name)
    }

    fun onDescriptionChanged(description: String) {
        _editorState.value = _editorState.value.copy(description = description)
    }

    fun onFilterModeChanged(mode: FilterMode) {
        _editorState.value = _editorState.value.copy(filterMode = mode)
    }

    fun onSearchQueryChanged(query: String) {
        _editorState.value = _editorState.value.copy(searchQuery = query)
    }

    fun onCategoryFilterSelected(category: AppCategory?) {
        _editorState.value = _editorState.value.copy(selectedCategory = category)
    }

    fun onAppToggle(packageName: String) {
        val current = _editorState.value.selectedPackages.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        _editorState.value = _editorState.value.copy(selectedPackages = current)
    }

    fun onSelectAllFiltered(apps: List<AppInfo>) {
        val current = _editorState.value.selectedPackages.toMutableSet()
        current.addAll(apps.map { it.packageName })
        _editorState.value = _editorState.value.copy(selectedPackages = current)
    }

    fun onClearAllSelected() {
        _editorState.value = _editorState.value.copy(selectedPackages = emptySet())
    }

    fun onLinkedTagSelected(tagUid: String?) {
        _editorState.value = _editorState.value.copy(linkedTagUid = tagUid)
    }

    fun onRequireTagToUnlockChanged(required: Boolean) {
        _editorState.value = _editorState.value.copy(requireTagToUnlock = required)
    }

    fun onEmergencyCooldownChanged(minutes: Int) {
        _editorState.value = _editorState.value.copy(emergencyCooldownMinutes = minutes)
    }

    fun saveProfile() {
        val state = _editorState.value
        if (state.name.isBlank()) return

        viewModelScope.launch {
            _editorState.value = _editorState.value.copy(isSaving = true)

            val unlockCondition = if (state.requireTagToUnlock) {
                UnlockCondition.RequireNfcTag(
                    requiredTagUid = state.linkedTagUid,
                    allowEmergencyUnlock = state.allowEmergencyUnlock,
                    emergencyCooldownMinutes = state.emergencyCooldownMinutes
                )
            } else {
                UnlockCondition.ManualOnly
            }

            val profile = Profile(
                id = state.profileId,
                name = state.name.trim(),
                description = state.description.trim(),
                colorHex = state.colorHex,
                filterMode = state.filterMode,
                blockedPackages = state.selectedPackages,
                linkedTagUid = state.linkedTagUid,
                unlockCondition = unlockCondition
            )

            profileRepository.saveProfile(profile)
            _editorState.value = _editorState.value.copy(isSaving = false, isSaved = true)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            profileRepository.deleteProfile(id)
        }
    }
}
