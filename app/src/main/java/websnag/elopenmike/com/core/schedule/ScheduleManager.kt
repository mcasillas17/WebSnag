package websnag.elopenmike.com.core.schedule

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.network.NetworkMonitor

class ScheduleManager(
    private val localDataStore: LocalDataStore,
    private val profileRepository: ProfileRepository,
    private val enforcementEngine: EnforcementEngine,
    private val coroutineScope: CoroutineScope,
    private val networkMonitor: NetworkMonitor? = null
) {
    private var scheduledAutoActiveProfileId: String? = null
    private var monitoringJob: Job? = null
    private var wifiObserverJob: Job? = null

    fun start() {
        monitoringJob?.cancel()
        wifiObserverJob?.cancel()

        // 1. Reactive WiFi network state observer
        if (networkMonitor != null) {
            wifiObserverJob = coroutineScope.launch {
                networkMonitor.wifiState.collect {
                    evaluateCurrentSchedules()
                }
            }
        }

        // 2. Periodic schedule clock check every 30 seconds
        monitoringJob = coroutineScope.launch {
            while (isActive) {
                evaluateCurrentSchedules()
                delay(30_000L)
            }
        }
    }

    suspend fun evaluateCurrentSchedules(nowEpochMs: Long = System.currentTimeMillis()) {
        val currentSchedules = localDataStore.schedulesFlow.first()
        val wifiState = networkMonitor?.wifiState?.value
        val isWifiConnected = wifiState?.isConnectedToWifi ?: true
        val currentSsid = wifiState?.currentSsid

        val activeSchedule = currentSchedules.firstOrNull {
            it.isCurrentlyActive(
                nowEpochMs = nowEpochMs,
                isWifiConnected = isWifiConnected,
                currentConnectedSsid = currentSsid
            )
        }

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
        wifiObserverJob?.cancel()
        wifiObserverJob = null
    }
}
