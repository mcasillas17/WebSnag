package websnag.elopenmike.com.core.enforcement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.data.EnforcementSnapshot
import websnag.elopenmike.com.core.data.currentStateFlow
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
    private val emergencyClock: EmergencyClock = EmergencyClock(),
    private val hasEnrolledNfcTag: suspend () -> Boolean = { false }
) {
    private val _enforcementState = MutableStateFlow(EnforcementState())
    val enforcementState: StateFlow<EnforcementState> = currentStateFlow(
        if (localDataStore == null) _enforcementState
        else combine(_enforcementState, localDataStore.storageRecoveryState) { _, _ -> Unit }
    ) { currentEnforcementState() }
    private val _endEvents = MutableSharedFlow<EndEvent>(extraBufferCapacity = 1)
    val endEvents: SharedFlow<EndEvent> = _endEvents

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
    private var runningRecovery: EmergencyRecovery? = null
    private var snapshot = EnforcementSnapshot(null, null)
    private val commands = Mutex()
    @Volatile private var stopped = false
    private val storageGeneration: Long
        get() = localDataStore?.storageRecoveryState?.value?.generation ?: 0L

    private val observerJob: Job = coroutineScope.launch {
        profileRepository.enforcementSnapshotFlow.collect {
            commands.withLock {
                if (stopped) return@withLock
                // A flow emission can have waited behind a newer command. Read the current pair,
                // not the queued value, before restoring anything.
                reloadSnapshot()?.let {
                    restoreEmergencyRecovery()
                }
            }
        }
    }

    private var recoveryObserverJob: Job? = null
    private var storageReadJob: Job? = null

    init {
        if (localDataStore != null) {
            // Monitor storage even if the repository is still serving an already-loaded profile.
            storageReadJob = coroutineScope.launch { localDataStore.emergencyRecoveryFlow.collect {} }
            recoveryObserverJob = coroutineScope.launch {
                // Failure is read directly, not copied into session state. A later successful
                // store read only permits an own reload; it cannot acknowledge it for this engine.
                localDataStore.storageRecoveryState.collect { recovery ->
                    if (!recovery.required && recovery.generation > _enforcementState.value.appliedStorageGeneration) {
                        commands.withLock {
                            reloadSnapshot()?.let {
                                restoreEmergencyRecovery()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Deliberate, user-initiated release of the unreadable-storage lockdown. Some initialization
     * failures (a lost Keystore key, an ambiguous stored reference) cannot be repaired by retrying
     * or by any approval, and blocking every non-exempt package forever would leave the device
     * unusable with no way out -- WebSnag never creates an unrecoverable lock. The caller is
     * responsible for the friction that precedes this; the release lasts only until persisted state
     * loads, and the failure itself stays reported.
     */
    fun pauseRecoveryLockdown() {
        _enforcementState.update {
            val recovery = localDataStore?.storageRecoveryState?.value
            it.copy(recoveryLockdownPaused = true,
                pausedStorageGeneration = recovery?.generation ?: 0L,
                pausedStorageEpisode = recovery?.episode ?: 0L)
        }
    }

    fun registerExemptPackage(packageName: String) {
        if (packageName.isNotBlank()) {
            systemExemptPackages = systemExemptPackages + packageName
        }
    }

    fun stop() {
        stopped = true
        observerJob.cancel()
        recoveryObserverJob?.cancel()
        storageReadJob?.cancel()
        emergencyTimerJob?.cancel()
    }

    private fun applySnapshot(
        current: EnforcementSnapshot,
        bindingLegacySession: Boolean = false,
        loadedGeneration: Long? = null
    ) {
        snapshot = current
        val profile = current.activeProfile
        val previousState = _enforcementState.value
        val sameSession = bindingLegacySession || (previousState.activeProfile?.id == profile?.id &&
            previousState.activeProfile?.sessionId == profile?.sessionId &&
            previousState.activeProfile?.activatedAtEpochMs == profile?.activatedAtEpochMs)
        if (!sameSession || previousState.activeProfile != profile || runningRecovery != current.recovery) {
            emergencyTimerJob?.cancel()
            emergencyTimerJob = null
            runningRecovery = null
        }
        if (profile != null && profile.isActive) {
            val packages = profile.blockedPackages
            if (!sameSession) sessionInterceptionCount = 0
            _enforcementState.update {
                it.copy(
                isBlockingActive = true,
                activeProfile = profile,
                filterMode = profile.filterMode,
                blockedPackages = packages,
                sessionStartedAtEpochMs = profile.activatedAtEpochMs ?: System.currentTimeMillis(),
                sessionUiKey = if (sameSession) previousState.sessionUiKey
                    else profile.sessionId ?: UUID.randomUUID().toString(),
                recoveryLockdownPaused = it.recoveryLockdownPaused && storageRecoveryRequired() &&
                    (loadedGeneration == null || loadedGeneration < it.pausedStorageGeneration),
                appliedStorageGeneration = loadedGeneration ?: it.appliedStorageGeneration,
                emergencyRecovery = current.recovery,
                emergencyCooldownActive = runningRecovery != null,
                remainingEmergencyMs = if (runningRecovery != null) previousState.remainingEmergencyMs else 0L,
                emergencyRecoveryError = if (sameSession) previousState.emergencyRecoveryError else null
            ) }
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
                    try {
                        localDataStore?.saveFocusSession(sessionRecord)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        _enforcementState.update { it.copy(emergencyRecoveryError = "Focus history could not be saved.") }
                    }
                }
            }

            sessionInterceptionCount = 0
            emergencyTimerJob?.cancel()
            _enforcementState.update {
                it.copy(
                isBlockingActive = false,
                activeProfile = null,
                filterMode = FilterMode.BLOCKLIST,
                blockedPackages = emptySet(),
                sessionStartedAtEpochMs = null,
                sessionUiKey = null,
                emergencyCooldownActive = false,
                emergencyCooldownStartEpochMs = null,
                emergencyRecovery = current.recovery,
                remainingEmergencyMs = 0,
                recoveryLockdownPaused = it.recoveryLockdownPaused && storageRecoveryRequired() &&
                    (loadedGeneration == null || loadedGeneration < it.pausedStorageGeneration),
                appliedStorageGeneration = loadedGeneration ?: it.appliedStorageGeneration
            ) }
        }
    }

    /**
     * Ultra-fast thread-safe package check used directly by the Accessibility Service.
     *
     * System exemptions are applied first, so emergency calling, the dialer, the launcher and
     * WebSnag itself stay reachable in every state. When persisted state cannot be read the check
     * then fails closed: an unreadable store must never be mistaken for "no active session" and
     * silently drop a lock the user configured. [pauseRecoveryLockdown] releases only that extra
     * blocking; an already-loaded session keeps its own policy.
     */
    fun isPackageBlocked(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (systemExemptPackages.contains(packageName)) return false
        val state = currentEnforcementState()
        // The lockdown only ever *widens* blocking. Releasing it must therefore not release a
        // session that was already loaded before the failure: ending that still requires the
        // profile's own unlock policy, never the recovery screen's typed phrase.
        if (state.recoveryLockdownInForce) return true
        if (!state.isBlockingActive) return false

        return when (state.filterMode) {
            FilterMode.BLOCKLIST -> state.blockedPackages.contains(packageName)
            FilterMode.ALLOWLIST -> !state.blockedPackages.contains(packageName)
        }
    }

    fun recordBlockedAttempt(packageName: String) {
        sessionInterceptionCount++
        _enforcementState.update { it.copy(
            lastBlockedPackageName = packageName,
            lastBlockedEpochMs = System.currentTimeMillis()
        ) }
    }

    suspend fun tryActivateProfile(profileId: String): Boolean {
        val generation = storageGeneration
        if (!commandsAvailable()) return false
        return commands.withLock {
            if (!commandsAvailable() || generation != storageGeneration) return@withLock false
            storageOperation {
                if (!hasEnrolledNfcTag()) return@storageOperation false
                profileRepository.setActiveProfile(profileId)
                applySnapshot(profileRepository.readEnforcementSnapshot())
                true
            } == true
        }
    }

    suspend fun requestEnd(profileId: String, request: EndRequest): Boolean {
        // No caller-supplied booleans are proof of a persisted cooldown.
        if (request is EndRequest.Emergency || !commandsAvailable()) return false
        val expectedSession = _enforcementState.value.activeProfile
        val generation = storageGeneration
        return commands.withLock {
            if (!commandsAvailable() || generation != storageGeneration) return@withLock false
            val current = readSnapshot() ?: return@withLock false
            val active = current.activeProfile ?: return@withLock false
            if (active != expectedSession || active.id != profileId ||
                !UnlockPolicy.canEnd(active.unlockCondition, request)) return@withLock false
            if (!commit(current, EnforcementSnapshot(null, null))) return@withLock false
            _endEvents.tryEmit(EndEvent(profileId, request.toEndReason()))
            true
        }
    }

    /**
     * Initiates intentional friction emergency cooldown.
     */
    suspend fun startEmergencyUnlock(
        intentionConfirmed: Boolean,
        sessionId: String? = _enforcementState.value.activeProfile?.sessionId,
        previousRequestId: String? = _enforcementState.value.emergencyRecovery?.requestId
    ): Boolean {
        if (!commandsAvailable()) return false
        val expectedProfile = _enforcementState.value.activeProfile
        val generation = storageGeneration
        return commands.withLock {
            if (!commandsAvailable() || generation != storageGeneration) return@withLock false
            val current = readSnapshot() ?: return@withLock false
            val active = current.activeProfile ?: return@withLock false
            if (active != expectedProfile || active.sessionId != sessionId ||
                current.recovery?.requestId != previousRequestId ||
                !UnlockPolicy.canStartEmergency(active.unlockCondition, intentionConfirmed)) return@withLock false
            // Repeated taps are idempotent; a restart requires a durably successful cancellation.
            if (current.recovery != null) {
                applySnapshot(current)
                restoreEmergencyRecovery()
                return@withLock runningRecovery != null
            }
            val elapsed = emergencyClock.elapsedRealtime()
            if (elapsed < 0) return@withLock recoveryError("Elapsed time is unavailable.")
            val boundProfile = active.copy(sessionId = active.sessionId ?: UUID.randomUUID().toString())
            val recovery = EmergencyRecovery(
                active.id, System.currentTimeMillis(),
                UnlockPolicy.emergencyDurationMs(active.unlockCondition)!!, intentionConfirmed,
                UUID.randomUUID().toString(), boundProfile.sessionId, elapsed, trustedBootId()
            )
            if (!commit(current, EnforcementSnapshot(boundProfile, recovery))) return@withLock false
            launchRecovery(recovery)
            true
        }
    }

    /** A stale cancel is scoped to its request, never a queued unqualified null write. */
    suspend fun cancelEmergencyUnlock(requestId: String): Boolean {
        if (!commandsAvailable()) return false
        val generation = storageGeneration
        return commands.withLock {
            if (!commandsAvailable() || generation != storageGeneration) return@withLock false
            val current = readSnapshot() ?: return@withLock false
            if (current.recovery?.requestId != requestId) return@withLock false
            commit(current, current.copy(recovery = null))
        }
    }

    private fun trustedBootId(): String? = emergencyClock.bootId()?.takeIf { it.isNotBlank() }

    private suspend fun restoreEmergencyRecovery() {
        val current = snapshot
        val recovery = current.recovery ?: return
        if (runningRecovery == recovery || !commandsAvailable()) return
        val active = current.activeProfile ?: return
        if (recovery.requestId.isNullOrBlank()) {
            // Legacy requests, including ones whose phrase is no longer authorized, remain
            // visible and cancellable. Giving one an identity does not grant elapsed credit.
            if (commit(current, current.copy(recovery = recovery.copy(requestId = UUID.randomUUID().toString())))) {
                restoreEmergencyRecovery()
            }
            return
        }
        if (active.id != recovery.profileId ||
            (recovery.sessionId != null && recovery.sessionId != active.sessionId)) {
            recoveryError("Recovery belongs to a different session. Cancel it before starting again.")
            return
        }
        if (!UnlockPolicy.canStartEmergency(active.unlockCondition, recovery.intentionConfirmed)) {
            recoveryError("This recovery is not authorized by the active profile.")
            return
        }
        val configured = UnlockPolicy.emergencyDurationMs(active.unlockCondition)!!
        val duration = recovery.durationMs.takeIf { it in 1..Int.MAX_VALUE.toLong() * 60_000L }
            ?.coerceAtLeast(configured) ?: configured
        val now = emergencyClock.elapsedRealtime()
        if (now < 0) {
            recoveryError("Elapsed time is unavailable.")
            return
        }
        val boot = trustedBootId()
        val trusted = boot != null && boot == recovery.bootId &&
            !recovery.requestId.isNullOrBlank() && !recovery.sessionId.isNullOrBlank() &&
            recovery.startedAtElapsedMs?.let { it in 0..now } == true &&
            recovery.durationMs == duration
        if (trusted) {
            launchRecovery(recovery)
        } else {
            // Reboot, unknown boot, legacy and invalid anchors receive NO wall-clock credit.
            // Persist the new anchor/session before any timer can complete.
            val bound = active.copy(sessionId = active.sessionId ?: UUID.randomUUID().toString())
            val restarted = recovery.copy(
                durationMs = duration, requestId = UUID.randomUUID().toString(),
                sessionId = bound.sessionId, startedAtElapsedMs = now, bootId = boot
            )
            if (commit(current, EnforcementSnapshot(bound, restarted))) launchRecovery(restarted)
        }
    }

    private fun launchRecovery(recovery: EmergencyRecovery) {
        emergencyTimerJob?.cancel()
        runningRecovery = recovery
        _enforcementState.update { it.copy(emergencyRecoveryError = null) }
        publishRemaining(recovery)
        emergencyTimerJob = coroutineScope.launch {
            val owner = currentCoroutineContext().job
            while (true) {
                val nextDelay = commands.withLock {
                    if (emergencyTimerJob !== owner || runningRecovery != recovery) return@withLock null
                    if (stopped) {
                        retireRecoveryTimer(owner)
                        return@withLock null
                    }
                    if (!commandsAvailable()) {
                        recoveryError("Saved data is unavailable. Your cooldown is kept and will resume after storage is repaired.")
                        return@withLock EMERGENCY_RETRY_DELAY_MS
                    }
                    val remaining = publishRemaining(recovery)
                    if (remaining > 0) return@withLock minOf(remaining, 1_000L)
                    val current = readSnapshot() ?: return@withLock EMERGENCY_RETRY_DELAY_MS
                    val active = current.activeProfile
                    if (current.recovery != recovery || active == null || active.sessionId != recovery.sessionId ||
                        active.id != recovery.profileId ||
                        recovery.durationMs < (UnlockPolicy.emergencyDurationMs(active.unlockCondition) ?: Long.MAX_VALUE) ||
                        !UnlockPolicy.canStartEmergency(active.unlockCondition, recovery.intentionConfirmed)) {
                        retireRecoveryTimer(owner)
                        applySnapshot(current)
                        restoreEmergencyRecovery()
                        return@withLock null
                    }
                    // Do not cancel this coroutine before DataStore returns its durable result.
                    if (storageOperation {
                            profileRepository.compareAndSetEnforcement(current, EnforcementSnapshot(null, null))
                        } == true) {
                        retireRecoveryTimer(owner)
                        applySnapshot(EnforcementSnapshot(null, null))
                        _enforcementState.update { it.copy(emergencyRecoveryError = null) }
                        _endEvents.tryEmit(EndEvent(recovery.profileId, EndReason.EMERGENCY))
                        return@withLock null
                    }
                    // A zero countdown is not a reason to spin or discard the completed wait.
                    // Every retry reads the current session/request/policy and commits with CAS.
                    EMERGENCY_RETRY_DELAY_MS
                }
                if (nextDelay == null) break
                delay(nextDelay)
            }
        }
    }

    /** Called under commands: retirement cannot race a repaired-state observer or replacement job. */
    private fun retireRecoveryTimer(owner: Job) {
        if (emergencyTimerJob !== owner) return
        runningRecovery = null
        emergencyTimerJob = null
        _enforcementState.update { it.copy(emergencyCooldownActive = false) }
    }

    private fun publishRemaining(recovery: EmergencyRecovery): Long {
        val now = emergencyClock.elapsedRealtime()
        val start = recovery.startedAtElapsedMs!!
        // Subtraction, not an overflowing start+duration deadline.
        val remaining = if (now < start) recovery.durationMs
            else (recovery.durationMs - (now - start)).coerceAtLeast(0)
        _enforcementState.update { it.copy(
            emergencyRecovery = recovery, emergencyCooldownActive = true,
            emergencyCooldownStartEpochMs = recovery.startedAtEpochMs,
            emergencyCooldownDurationMs = recovery.durationMs,
            remainingEmergencyMs = remaining
        ) }
        return remaining
    }

    private fun commandsAvailable(): Boolean = !stopped && !storageRecoveryRequired()

    private fun storageRecoveryRequired(): Boolean {
        val acknowledged = _enforcementState.value.appliedStorageGeneration
        val recovery = localDataStore?.storageRecoveryState?.value ?: return false
        return recovery.required || recovery.generation > acknowledged
    }

    private fun currentEnforcementState(): EnforcementState {
        // Policy and its own-reload proof are one atomic snapshot. The current store failure
        // always takes precedence, including before any flow collector has run.
        val state = _enforcementState.value
        val recovery = localDataStore?.storageRecoveryState?.value
        val required = recovery != null && (recovery.required || recovery.generation > state.appliedStorageGeneration)
        val paused = state.recoveryLockdownPaused && state.pausedStorageEpisode == (recovery?.episode ?: 0L)
        return if (state.storageRecoveryRequired == required && state.recoveryLockdownPaused == paused) state
        else state.copy(storageRecoveryRequired = required, recoveryLockdownPaused = paused)
    }

    private fun recoveryError(message: String): Boolean {
        _enforcementState.update { it.copy(emergencyRecoveryError = message) }
        return false
    }

    private suspend fun <T> storageOperation(operation: suspend () -> T): T? = try {
        withTimeoutOrNull(5_000) { operation() }.also {
            if (it == null) recoveryError("Saved data did not respond. The request was not completed.")
        }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        recoveryError("Saved data could not be updated. The request was not completed.")
        null
    }

    private suspend fun readSnapshot(): EnforcementSnapshot? =
        storageOperation { profileRepository.readEnforcementSnapshot() }

    private suspend fun reloadSnapshot(): EnforcementSnapshot? {
        val before = localDataStore?.storageRecoveryState?.value
        val current = readSnapshot() ?: return null
        val after = localDataStore?.storageRecoveryState?.value
        val loadedGeneration = after?.generation?.takeIf { before == after && !after.required }
        applySnapshot(current, loadedGeneration = loadedGeneration)
        return current
    }

    private suspend fun commit(expected: EnforcementSnapshot, updated: EnforcementSnapshot): Boolean {
        if (storageOperation { profileRepository.compareAndSetEnforcement(expected, updated) } != true) return false
        val oldProfile = expected.activeProfile
        val newProfile = updated.activeProfile
        // Only our successful binding transaction retains this presentation identity. Comparing
        // wall timestamps alone would also retain a genuinely reactivated profile's old dialog.
        val bindingLegacySession = oldProfile != null && oldProfile.sessionId == null &&
            newProfile?.sessionId != null && newProfile.copy(sessionId = null) == oldProfile &&
            updated.recovery?.sessionId == newProfile.sessionId
        applySnapshot(updated, bindingLegacySession)
        _enforcementState.update { it.copy(emergencyRecoveryError = null) }
        return true
    }

    private fun EndRequest.toEndReason(): EndReason = when (this) {
        is EndRequest.Nfc -> EndReason.NFC
        EndRequest.Manual -> EndReason.MANUAL
        is EndRequest.Emergency -> EndReason.EMERGENCY
        EndRequest.ScheduleEnded -> EndReason.SCHEDULE_END
    }

    companion object {
        private const val EMERGENCY_RETRY_DELAY_MS = 5_000L

        @Volatile
        private var instance: EnforcementEngine? = null

        fun get(): EnforcementEngine? = instance

        fun initialize(engine: EnforcementEngine): EnforcementEngine {
            instance = engine
            return engine
        }
    }
}
