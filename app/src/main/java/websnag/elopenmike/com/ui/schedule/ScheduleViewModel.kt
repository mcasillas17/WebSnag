package websnag.elopenmike.com.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleEndMode
import websnag.elopenmike.com.core.model.ScheduleRecord
import websnag.elopenmike.com.core.network.NetworkMonitor
import websnag.elopenmike.com.core.network.WifiState
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

data class ScheduleUiState(
    val schedules: List<ScheduleRecord> = emptyList(),
    val availableProfiles: List<Profile> = emptyList(),
    val activeSchedulesCount: Int = 0,
    val errorMessage: String? = null
)

class ScheduleViewModel(
    private val localDataStore: LocalDataStore,
    private val profileRepository: ProfileRepository,
    private val networkMonitor: NetworkMonitor? = null,
    private val enforcementEngine: EnforcementEngine
) : ViewModel() {

    val wifiState: StateFlow<WifiState> =
        networkMonitor?.wifiState ?: MutableStateFlow(WifiState())

    fun refreshWifiState() {
        networkMonitor?.refresh()
    }

    val uiState: StateFlow<ScheduleUiState> = combine(
        localDataStore.schedulesFlow,
        profileRepository.profilesFlow
    ) { schedules, profiles ->
        ScheduleUiState(
            schedules = schedules,
            availableProfiles = profiles,
            activeSchedulesCount = schedules.count { it.isEnabled }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

    fun toggleSchedule(scheduleId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            val schedule = localDataStore.schedulesFlow.first().firstOrNull { it.id == scheduleId }
            if (schedule?.profileId == enforcementEngine.enforcementState.value.activeProfile?.id) return@launch
            localDataStore.toggleSchedule(scheduleId, isEnabled)
        }
    }

    fun deleteSchedule(scheduleId: String) {
        viewModelScope.launch {
            val schedule = localDataStore.schedulesFlow.first().firstOrNull { it.id == scheduleId }
            if (schedule?.profileId == enforcementEngine.enforcementState.value.activeProfile?.id) return@launch
            localDataStore.deleteSchedule(scheduleId)
        }
    }

    fun saveSchedule(
        id: String?,
        name: String,
        profileId: String,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        endMode: ScheduleEndMode = ScheduleEndMode.AT_TIME,
        requiresWifi: Boolean = false,
        wifiSsid: String? = null,
        daysOfWeek: Set<ScheduleDay>,
        isEnabled: Boolean = true
    ) {
        viewModelScope.launch {
            val activeProfileId = enforcementEngine.enforcementState.value.activeProfile?.id
            val existing = id?.let { existingId ->
                localDataStore.schedulesFlow.first().firstOrNull { it.id == existingId }
            }
            if (profileId == activeProfileId || existing?.profileId == activeProfileId) return@launch
            val profile = profileRepository.getProfileById(profileId)
            val schedule = ScheduleRecord(
                id = id ?: "sched-${UUID.randomUUID()}",
                name = name.ifBlank { profile?.name ?: "Focus Routine" },
                profileId = profileId,
                profileName = profile?.name ?: "Focus Mode",
                filterMode = profile?.filterMode ?: websnag.elopenmike.com.core.model.FilterMode.BLOCKLIST,
                startHour = startHour,
                startMinute = startMinute,
                endHour = endHour,
                endMinute = endMinute,
                endMode = endMode,
                requiresWifi = requiresWifi,
                wifiSsid = wifiSsid?.trim(),
                daysOfWeek = daysOfWeek,
                isEnabled = isEnabled
            )
            localDataStore.saveSchedule(schedule)
        }
    }
}
