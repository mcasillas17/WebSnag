package org.websnag.core.enforcement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.websnag.core.data.ProfileRepository
import org.websnag.core.model.EnforcementState
import org.websnag.core.model.Profile
import org.websnag.core.model.UnlockCondition

/**
 * Central coordinator maintaining active blocking state and evaluating enforcement rules.
 */
class EnforcementEngine(
    private val profileRepository: ProfileRepository,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _enforcementState = MutableStateFlow(EnforcementState())
    val enforcementState: StateFlow<EnforcementState> = _enforcementState.asStateFlow()

    @Volatile
    private var activeBlockedPackagesCache: Set<String> = emptySet()

    private var emergencyTimerJob: Job? = null

    private val observerJob: Job = profileRepository.activeProfileFlow.onEach { activeProfile ->
        updateFromActiveProfile(activeProfile)
    }.launchIn(coroutineScope)

    fun stop() {
        observerJob.cancel()
        emergencyTimerJob?.cancel()
    }

    private fun updateFromActiveProfile(profile: Profile?) {
        if (profile != null && profile.isActive) {
            val blocked = profile.blockedPackages
            activeBlockedPackagesCache = blocked
            _enforcementState.value = _enforcementState.value.copy(
                isBlockingActive = true,
                activeProfile = profile,
                blockedPackages = blocked
            )
        } else {
            activeBlockedPackagesCache = emptySet()
            emergencyTimerJob?.cancel()
            _enforcementState.value = _enforcementState.value.copy(
                isBlockingActive = false,
                activeProfile = null,
                blockedPackages = emptySet(),
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
        return activeBlockedPackagesCache.contains(packageName)
    }

    fun recordBlockedAttempt(packageName: String) {
        _enforcementState.value = _enforcementState.value.copy(
            lastBlockedPackageName = packageName,
            lastBlockedEpochMs = System.currentTimeMillis()
        )
    }

    suspend fun activateProfile(profileId: String) {
        profileRepository.setActiveProfile(profileId)
    }

    suspend fun deactivateProfile(profileId: String) {
        emergencyTimerJob?.cancel()
        profileRepository.setActiveProfile(null)
    }

    /**
     * Initiates intentional friction emergency cooldown.
     */
    fun startEmergencyUnlock(cooldownMinutes: Int = 5, onCompleted: suspend () -> Unit) {
        val durationMs = cooldownMinutes * 60 * 1000L
        val startEpoch = System.currentTimeMillis()

        emergencyTimerJob?.cancel()
        _enforcementState.value = _enforcementState.value.copy(
            emergencyCooldownActive = true,
            emergencyCooldownStartEpochMs = startEpoch,
            emergencyCooldownDurationMs = durationMs
        )

        emergencyTimerJob = coroutineScope.launch {
            delay(durationMs)
            _enforcementState.value = _enforcementState.value.copy(
                emergencyCooldownActive = false,
                emergencyCooldownStartEpochMs = null
            )
            val currentActive = _enforcementState.value.activeProfile
            if (currentActive != null) {
                deactivateProfile(currentActive.id)
            }
            onCompleted()
        }
    }

    fun cancelEmergencyUnlock() {
        emergencyTimerJob?.cancel()
        _enforcementState.value = _enforcementState.value.copy(
            emergencyCooldownActive = false,
            emergencyCooldownStartEpochMs = null
        )
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
