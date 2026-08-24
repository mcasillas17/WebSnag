package org.websnag.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.websnag.core.data.LocalDataStore
import org.websnag.core.data.ProfileRepository
import org.websnag.core.model.Profile
import org.websnag.core.model.ScheduleRecord
import java.util.UUID

data class ScheduleUiState(
    val schedules: List<ScheduleRecord> = emptyList(),
    val availableProfiles: List<Profile> = emptyList(),
    val activeSchedulesCount: Int = 0
)

class ScheduleViewModel(
    private val localDataStore: LocalDataStore,
    private val profileRepository: ProfileRepository
) : ViewModel() {

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
            localDataStore.toggleSchedule(scheduleId, isEnabled)
        }
    }

    fun deleteSchedule(scheduleId: String) {
        viewModelScope.launch {
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
        daysOfWeek: Set<org.websnag.core.model.ScheduleDay>,
        isEnabled: Boolean = true
    ) {
        viewModelScope.launch {
            val profile = profileRepository.getProfileById(profileId)
            val schedule = ScheduleRecord(
                id = id ?: "sched-${UUID.randomUUID()}",
                name = name.ifBlank { profile?.name ?: "Focus Routine" },
                profileId = profileId,
                profileName = profile?.name ?: "Focus Mode",
                filterMode = profile?.filterMode ?: org.websnag.core.model.FilterMode.BLOCKLIST,
                startHour = startHour,
                startMinute = startMinute,
                endHour = endHour,
                endMinute = endMinute,
                daysOfWeek = daysOfWeek,
                isEnabled = isEnabled
            )
            localDataStore.saveSchedule(schedule)
        }
    }
}
