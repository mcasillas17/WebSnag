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
import org.websnag.core.data.LocalDataStore
import org.websnag.core.data.ProfileRepository
import org.websnag.core.model.EnforcementState
import org.websnag.core.model.FilterMode
import org.websnag.core.model.FocusSessionRecord
import org.websnag.core.model.Profile
import org.websnag.core.model.UnlockCondition
import java.util.UUID

/**
 * Central coordinator maintaining active blocking state and evaluating enforcement rules.
 */
class EnforcementEngine(
    private val profileRepository: ProfileRepository,
    private val localDataStore: LocalDataStore? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _enforcementState = MutableStateFlow(EnforcementState())
    val enforcementState: StateFlow<EnforcementState> = _enforcementState.asStateFlow()

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
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "org.websnag"
    )

    private var emergencyTimerJob: Job? = null

    private val observerJob: Job = profileRepository.activeProfileFlow.onEach { activeProfile ->
        updateFromActiveProfile(activeProfile)
    }.launchIn(coroutineScope)

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
