package websnag.elopenmike.com.core.schedule

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.network.NetworkMonitor
import websnag.elopenmike.com.core.enforcement.EndReason
import websnag.elopenmike.com.core.enforcement.EndRequest

class ScheduleManager(
    private val localDataStore: LocalDataStore,
    private val profileRepository: ProfileRepository,
    private val enforcementEngine: EnforcementEngine,
    private val coroutineScope: CoroutineScope,
    private val networkMonitor: NetworkMonitor? = null,
    private val alarmCoordinator: ScheduleAlarmCoordinator? = null
) {
    fun start() {
        if (networkMonitor != null) {
            coroutineScope.launch {
                networkMonitor.wifiState.collect {
                    evaluateCurrentSchedules()
                    reschedule()
                }
            }
        }
        coroutineScope.launch {
            localDataStore.schedulesFlow.collect {
                evaluateCurrentSchedules()
                reschedule()
            }
        }
        coroutineScope.launch {
            enforcementEngine.endEvents.collect { event ->
                if (event.reason != EndReason.SCHEDULE_END) {
                    val occurrence = localDataStore.activeScheduleOccurrenceFlow.first()
                    if (occurrence?.profileId == event.profileId) {
                        localDataStore.saveActiveScheduleOccurrence(
                            occurrence.copy(dismissed = true, endReason = event.reason.name)
                        )
                    }
                }
            }
        }
    }

    suspend fun evaluateCurrentSchedules(nowEpochMs: Long = System.currentTimeMillis()) {
        val currentSchedules = localDataStore.schedulesFlow.first()
        val wifiState = networkMonitor?.wifiState?.value
        val isWifiConnected = wifiState?.isConnectedToWifi ?: true
        val currentSsid = wifiState?.currentSsid

        val activeSchedule = currentSchedules.filter {
            it.isCurrentlyActive(
                nowEpochMs = nowEpochMs,
                isWifiConnected = isWifiConnected,
                currentConnectedSsid = currentSsid
            )
        }.sortedBy { it.id }.firstOrNull()
        val storedOccurrence = localDataStore.activeScheduleOccurrenceFlow.first()

        val currentState = enforcementEngine.enforcementState.value

        if (activeSchedule != null) {
            val occurrence = ScheduleReconciler.occurrenceFor(activeSchedule, nowEpochMs)
            if (storedOccurrence != null &&
                storedOccurrence.occurrenceStartEpochMs != occurrence?.occurrenceStartEpochMs &&
                currentState.activeProfile?.id == storedOccurrence.profileId
            ) {
                if (storedOccurrence.profileId != activeSchedule.profileId) {
                    enforcementEngine.requestEnd(storedOccurrence.profileId, EndRequest.ScheduleEnded)
                    localDataStore.saveActiveScheduleOccurrence(null)
                    return
                }
                localDataStore.saveActiveScheduleOccurrence(occurrence)
            }
            val isDismissedCurrentOccurrence = storedOccurrence?.let {
                it.scheduleId == occurrence?.scheduleId &&
                    it.occurrenceStartEpochMs == occurrence.occurrenceStartEpochMs &&
                    it.dismissed
            } == true
            if (currentState.activeProfile == null && !isDismissedCurrentOccurrence) {
                val profile = profileRepository.getProfileById(activeSchedule.profileId)
                if (profile != null) {
                    if (enforcementEngine.tryActivateProfile(profile.id)) {
                        localDataStore.saveActiveScheduleOccurrence(occurrence)
                    }
                }
            }
        } else {
            if (storedOccurrence != null && currentState.activeProfile?.id == storedOccurrence.profileId) {
                enforcementEngine.requestEnd(storedOccurrence.profileId, EndRequest.ScheduleEnded)
            }
            if (storedOccurrence != null) localDataStore.saveActiveScheduleOccurrence(null)
        }
    }

    fun reconcileNow(onComplete: () -> Unit = {}) {
        coroutineScope.launch {
            evaluateCurrentSchedules()
            onComplete()
        }
    }

    fun reschedule() {
        coroutineScope.launch {
            alarmCoordinator?.scheduleNext(localDataStore.schedulesFlow.first())
        }
    }
}
