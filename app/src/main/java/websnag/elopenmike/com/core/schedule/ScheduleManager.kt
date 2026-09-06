package websnag.elopenmike.com.core.schedule

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
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
                    // Bounded like reconcileNow: wifiState emits immediately at process start, the
                    // same start at which a failing migration leaves the store unreadable. An
                    // unbounded pass would stick here, conflate later Wi-Fi transitions away, and
                    // finally reconcile against the timestamp it captured before the failure.
                    withTimeoutOrNull(UNREADABLE_STORAGE_TIMEOUT_MS) { evaluateCurrentSchedules() }
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
        val outcome = reconcileSchedules(nowEpochMs)
        localDataStore.saveScheduleReconciliation(nowEpochMs, outcome)
    }

    private suspend fun reconcileSchedules(nowEpochMs: Long): ReconciliationOutcome {
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
                    return ReconciliationOutcome.ENDED
                }
                localDataStore.saveActiveScheduleOccurrence(occurrence)
            }
            val isDismissedCurrentOccurrence = storedOccurrence?.let {
                it.scheduleId == occurrence?.scheduleId &&
                    it.occurrenceStartEpochMs == occurrence.occurrenceStartEpochMs &&
                    it.dismissed
            } == true
            return if (currentState.activeProfile == null && !isDismissedCurrentOccurrence) {
                // No profile currently active for this occurrence: attempt activation. The
                // profile lookup and activation results are classified by the pure selector below
                // rather than inline, so a missing profile and a rejected activation (e.g.
                // required NFC tag not enrolled) are reported distinctly.
                val profile = profileRepository.getProfileById(activeSchedule.profileId)
                val activationSucceeded = profile != null && enforcementEngine.tryActivateProfile(profile.id)
                if (activationSucceeded) {
                    localDataStore.saveActiveScheduleOccurrence(occurrence)
                }
                ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                    isDismissedCurrentOccurrence = false,
                    hasActiveProfile = false,
                    activeProfileMatchesScheduledProfile = false,
                    profileLookupSucceeded = profile != null,
                    activationSucceeded = activationSucceeded
                )
            } else {
                ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                    isDismissedCurrentOccurrence = isDismissedCurrentOccurrence,
                    hasActiveProfile = currentState.activeProfile != null,
                    activeProfileMatchesScheduledProfile = currentState.activeProfile?.id == activeSchedule.profileId,
                    profileLookupSucceeded = true,
                    activationSucceeded = true
                )
            }
        } else {
            if (storedOccurrence != null && currentState.activeProfile?.id == storedOccurrence.profileId) {
                enforcementEngine.requestEnd(storedOccurrence.profileId, EndRequest.ScheduleEnded)
            }
            return if (storedOccurrence != null) {
                localDataStore.saveActiveScheduleOccurrence(null)
                ReconciliationOutcome.ENDED
            } else {
                ReconciliationOutcome.NO_CHANGE
            }
        }
    }

    /**
     * [onComplete] always runs, even when persisted reads are waiting for storage recovery.
     * [websnag.elopenmike.com.core.schedule.ScheduleAlarmReceiver] finishes its `goAsync()`
     * PendingResult from that callback, and BOOT_COMPLETED / MY_PACKAGE_REPLACED are exactly the
     * deliveries that coincide with a failing startup migration, so an open-ended wait here would
     * leave the broadcast unfinished.
     */
    fun reconcileNow(onComplete: () -> Unit = {}) {
        coroutineScope.launch {
            withTimeoutOrNull(UNREADABLE_STORAGE_TIMEOUT_MS) { evaluateCurrentSchedules() }
            onComplete()
        }
    }

    fun reschedule() {
        coroutineScope.launch {
            withTimeoutOrNull(UNREADABLE_STORAGE_TIMEOUT_MS) {
                alarmCoordinator?.scheduleNext(localDataStore.schedulesFlow.first())
            }
        }
    }

    private companion object {
        /**
         * Bound on a single reconcile pass. Reads park until storage recovers, so without this an
         * alarm or system broadcast would accumulate a coroutine that never returns. Comfortably
         * above a normal reconcile and below the broadcast-completion budget.
         */
        const val UNREADABLE_STORAGE_TIMEOUT_MS = 5_000L
    }
}
