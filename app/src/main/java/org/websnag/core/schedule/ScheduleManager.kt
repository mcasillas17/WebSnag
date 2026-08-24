package org.websnag.core.schedule

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.websnag.core.data.LocalDataStore
import org.websnag.core.data.ProfileRepository
import org.websnag.core.enforcement.EnforcementEngine

class ScheduleManager(
    private val localDataStore: LocalDataStore,
    private val profileRepository: ProfileRepository,
    private val enforcementEngine: EnforcementEngine,
    private val coroutineScope: CoroutineScope
) {
    private var scheduledAutoActiveProfileId: String? = null
    private var monitoringJob: Job? = null

    fun start() {
        monitoringJob?.cancel()
        monitoringJob = coroutineScope.launch {
            // Periodic check every 30 seconds
            while (isActive) {
                evaluateCurrentSchedules()
                delay(30_000L)
            }
        }
    }

    suspend fun evaluateCurrentSchedules(nowEpochMs: Long = System.currentTimeMillis()) {
        val currentSchedules = localDataStore.schedulesFlow.first()
        val activeSchedule = currentSchedules.firstOrNull { it.isCurrentlyActive(nowEpochMs) }

        val currentState = enforcementEngine.enforcementState.value

        if (activeSchedule != null) {
            // A schedule window is active
            if (currentState.activeProfile == null) {
                val profile = profileRepository.getProfileById(activeSchedule.profileId)
                if (profile != null) {
                    scheduledAutoActiveProfileId = profile.id
                    enforcementEngine.activateProfile(profile.id)
                }
            }
        } else {
            // No schedule is active
            // If the current profile was activated automatically by a schedule, de-escalate it
            val autoId = scheduledAutoActiveProfileId
            if (autoId != null && currentState.activeProfile?.id == autoId) {
                scheduledAutoActiveProfileId = null
                enforcementEngine.deactivateProfile(autoId)
            }
        }
    }

    fun stop() {
        monitoringJob?.cancel()
        monitoringJob = null
    }
}
