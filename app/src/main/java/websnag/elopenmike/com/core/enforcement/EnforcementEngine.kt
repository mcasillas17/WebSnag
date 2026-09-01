package websnag.elopenmike.com.core.enforcement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.model.EnforcementState
import websnag.elopenmike.com.core.model.EmergencyRecovery
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.FocusSessionRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import java.util.UUID

/**
 * Central coordinator maintaining active blocking state and evaluating enforcement rules.
 */
class EnforcementEngine(
    private val profileRepository: ProfileRepository,
    private val localDataStore: LocalDataStore? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val hasEnrolledNfcTag: suspend () -> Boolean = { false }
) {
    private val _enforcementState = MutableStateFlow(EnforcementState())
    val enforcementState: StateFlow<EnforcementState> = _enforcementState.asStateFlow()
    private val _endEvents = MutableSharedFlow<EndEvent>(extraBufferCapacity = 1)
    val endEvents: SharedFlow<EndEvent> = _endEvents

    @Volatile
    private var activePackagesCache: Set<String> = emptySet()

    @Volatile
    private var activeFilterMode: FilterMode = FilterMode.BLOCKLIST

    @Volatile
    private var sessionInterceptionCount: Int = 0

    @Volatile
    private var systemExemptPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.android.phone",
        "com.android.telecom",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.emergency",
        "websnag.elopenmike.com"
    )

    private var emergencyTimerJob: Job? = null
    private var pendingRecovery: EmergencyRecovery? = null

    private val observerJob: Job = profileRepository.activeProfileFlow.onEach { activeProfile ->
        updateFromActiveProfile(activeProfile)
    }.launchIn(coroutineScope)

    init {
        if (localDataStore != null) {
            coroutineScope.launch {
                localDataStore.emergencyRecoveryFlow.collect { recovery ->
                    pendingRecovery = recovery
                    if (recovery != null) restoreEmergencyRecovery(recovery)
                }
            }
        }
    }

    fun registerExemptPackage(packageName: String) {
        if (packageName.isNotBlank()) {
            systemExemptPackages = systemExemptPackages + packageName
        }
    }

    fun stop() {
        observerJob.cancel()
        emergencyTimerJob?.cancel()
    }

    private fun updateFromActiveProfile(profile: Profile?) {
        val previousState = _enforcementState.value

        if (profile != null && profile.isActive) {
            val packages = profile.blockedPackages
            activePackagesCache = packages
            activeFilterMode = profile.filterMode
            sessionInterceptionCount = 0
            _enforcementState.value = _enforcementState.value.copy(
                isBlockingActive = true,
                activeProfile = profile,
                filterMode = profile.filterMode,
                blockedPackages = packages,
                sessionStartedAtEpochMs = profile.activatedAtEpochMs ?: System.currentTimeMillis()
            )
            pendingRecovery?.let(::restoreEmergencyRecovery)
        } else {
            // Log completed session if we were actively blocking
            if (previousState.isBlockingActive && previousState.activeProfile != null) {
                val startedAt = previousState.sessionStartedAtEpochMs ?: System.currentTimeMillis()
                val endedAt = System.currentTimeMillis()
                val durationSec = maxOf(1L, (endedAt - startedAt) / 1000L)
                val completedProfile = previousState.activeProfile

                val sessionRecord = FocusSessionRecord(
                    id = UUID.randomUUID().toString(),
                    profileId = completedProfile.id,
                    profileName = completedProfile.name,
                    filterMode = completedProfile.filterMode,
                    startTimeEpochMs = startedAt,
                    endTimeEpochMs = endedAt,
                    durationSeconds = durationSec,
                    interceptionsPrevented = sessionInterceptionCount
                )

                coroutineScope.launch {
                    localDataStore?.saveFocusSession(sessionRecord)
                }
            }

            activePackagesCache = emptySet()
            activeFilterMode = FilterMode.BLOCKLIST
            sessionInterceptionCount = 0
            emergencyTimerJob?.cancel()
            _enforcementState.value = _enforcementState.value.copy(
                isBlockingActive = false,
                activeProfile = null,
                filterMode = FilterMode.BLOCKLIST,
                blockedPackages = emptySet(),
                sessionStartedAtEpochMs = null,
                emergencyCooldownActive = false,
                emergencyCooldownStartEpochMs = null
            )
        }
    }

    /**
     * Ultra-fast thread-safe package check used directly by the Accessibility Service.
     */
    fun isPackageBlocked(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (!_enforcementState.value.isBlockingActive) return false
        if (systemExemptPackages.contains(packageName)) return false

        return when (activeFilterMode) {
            FilterMode.BLOCKLIST -> activePackagesCache.contains(packageName)
            FilterMode.ALLOWLIST -> !activePackagesCache.contains(packageName)
        }
    }

    fun recordBlockedAttempt(packageName: String) {
        sessionInterceptionCount++
        _enforcementState.value = _enforcementState.value.copy(
            lastBlockedPackageName = packageName,
            lastBlockedEpochMs = System.currentTimeMillis()
        )
    }

    suspend fun tryActivateProfile(profileId: String): Boolean {
        if (!hasEnrolledNfcTag()) return false
        profileRepository.setActiveProfile(profileId)
        return true
    }

    suspend fun requestEnd(profileId: String, request: EndRequest): Boolean {
        val activeProfile = _enforcementState.value.activeProfile
        if (activeProfile?.id != profileId || !UnlockPolicy.canEnd(activeProfile.unlockCondition, request)) {
            return false
        }
        emergencyTimerJob?.cancel()
        profileRepository.setActiveProfile(null)
        localDataStore?.saveEmergencyRecovery(null)
        _endEvents.tryEmit(EndEvent(profileId, request.toEndReason()))
        return true
    }

    /**
     * Initiates intentional friction emergency cooldown.
     */
    fun startEmergencyUnlock(intentionConfirmed: Boolean, onCompleted: suspend () -> Unit): Boolean {
        val active = _enforcementState.value.activeProfile ?: return false
        val condition = active.unlockCondition as? UnlockCondition.RequireNfcTag ?: return false
        if (!condition.allowEmergencyUnlock || (condition.requireIntentionPhrase && !intentionConfirmed)) return false

        val durationMs = condition.emergencyCooldownMinutes.coerceAtLeast(1) * 60 * 1000L
        val startEpoch = System.currentTimeMillis()
        val recovery = EmergencyRecovery(active.id, startEpoch, durationMs, intentionConfirmed)
        pendingRecovery = recovery

        emergencyTimerJob?.cancel()
        _enforcementState.value = _enforcementState.value.copy(
            emergencyCooldownActive = true,
            emergencyCooldownStartEpochMs = startEpoch,
            emergencyCooldownDurationMs = durationMs
        )

        emergencyTimerJob = coroutineScope.launch {
            localDataStore?.saveEmergencyRecovery(recovery)
            completeEmergencyRecovery(recovery, onCompleted)
        }
        return true
    }

    fun cancelEmergencyUnlock() {
        emergencyTimerJob?.cancel()
        pendingRecovery = null
        coroutineScope.launch { localDataStore?.saveEmergencyRecovery(null) }
        _enforcementState.value = _enforcementState.value.copy(
            emergencyCooldownActive = false,
            emergencyCooldownStartEpochMs = null
        )
    }

    private fun restoreEmergencyRecovery(recovery: EmergencyRecovery) {
        if (_enforcementState.value.activeProfile?.id != recovery.profileId) return
        if (emergencyTimerJob?.isActive == true) return
        emergencyTimerJob?.cancel()
        _enforcementState.value = _enforcementState.value.copy(
            emergencyCooldownActive = true,
            emergencyCooldownStartEpochMs = recovery.startedAtEpochMs,
            emergencyCooldownDurationMs = recovery.durationMs
        )
        emergencyTimerJob = coroutineScope.launch { completeEmergencyRecovery(recovery) }
    }

    private suspend fun completeEmergencyRecovery(recovery: EmergencyRecovery, onCompleted: (suspend () -> Unit)? = null) {
        delay((recovery.completesAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
        val ended = requestEnd(
            recovery.profileId,
            EndRequest.Emergency(cooldownComplete = true, intentionConfirmed = recovery.intentionConfirmed)
        )
        if (ended) onCompleted?.invoke()
    }

    private fun EndRequest.toEndReason(): EndReason = when (this) {
        is EndRequest.Nfc -> EndReason.NFC
        EndRequest.Manual -> EndReason.MANUAL
        is EndRequest.Emergency -> EndReason.EMERGENCY
        EndRequest.ScheduleEnded -> EndReason.SCHEDULE_END
    }

    companion object {
        @Volatile
        private var instance: EnforcementEngine? = null

        fun get(): EnforcementEngine? = instance

        fun initialize(engine: EnforcementEngine): EnforcementEngine {
            instance = engine
            return engine
        }
    }
}
