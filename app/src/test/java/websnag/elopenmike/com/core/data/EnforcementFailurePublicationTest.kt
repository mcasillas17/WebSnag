package websnag.elopenmike.com.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import websnag.elopenmike.com.FakeProfileRepository
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.EnforcementState
import websnag.elopenmike.com.core.model.Profile
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class EnforcementFailurePublicationTest {
    private fun inOwnReload(): Boolean = Thread.currentThread().stackTrace.any {
        it.className == EnforcementEngine::class.java.name && it.methodName.startsWith("reloadSnapshot")
    }

    @Test fun activeSnapshotCannotOverwriteANewerStorageFailure() = runBlocking {
        verifyPublication(active = true)
    }

    @Test fun inactiveSnapshotCannotOverwriteANewerStorageFailure() = runBlocking {
        verifyPublication(active = false)
    }

    @Test fun activeRetryCannotClearAnAlreadyActiveLockdownAfterASecondFailure() = runBlocking {
        verifyPublication(active = true, alreadyFailed = true)
    }

    @Test fun inactiveRetryCannotClearAnAlreadyActiveLockdownAfterASecondFailure() = runBlocking {
        verifyPublication(active = false, alreadyFailed = true)
    }

    @Test fun activeLockdownSurvivesAConflatedRetryAndSecondFailure() = runBlocking {
        verifyConflatedFailure(active = true)
    }

    @Test fun inactiveLockdownSurvivesAConflatedRetryAndSecondFailure() = runBlocking {
        verifyConflatedFailure(active = false)
    }

    @Test fun activePauseOfSecondFailureSurvivesOlderReloadPublication() = runBlocking {
        verifyConflatedFailure(active = true, pauseBeforePublication = true)
    }

    @Test fun inactivePauseOfSecondFailureSurvivesOlderReloadPublication() = runBlocking {
        verifyConflatedFailure(active = false, pauseBeforePublication = true)
    }

    private suspend fun verifyConflatedFailure(active: Boolean, pauseBeforePublication: Boolean = false) {
        val reads = MutableStateFlow<Result<Preferences>>(Result.success(emptyPreferences()))
        val readReady = CompletableDeferred<Unit>()
        val local = LocalDataStore(object : DataStore<Preferences> {
            override val data = flow {
                reads.collect { emit(it.getOrThrow()); readReady.complete(Unit) }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("synthetic read-only store")
        })
        val raw = local.storageRecoveryState
        val initialRecoveryDelivered = CompletableDeferred<Unit>()
        val collectorPaused = CompletableDeferred<Unit>()
        val resumeCollector = CompletableDeferred<Unit>()
        val secondFailurePublished = CompletableDeferred<Unit>()
        val resumePublication = CompletableDeferred<Unit>()
        val snapshotPublished = CompletableDeferred<Unit>()
        val firstRead = CompletableDeferred<Unit>()
        val armRead = AtomicBoolean(false)
        val skipReloadReads = AtomicInteger(1)
        val markSnapshot = AtomicBoolean(false)
        val delivered = CopyOnWriteArrayList<StorageRecoveryState>()
        val newFailureDelivered = CompletableDeferred<Long>()
        lateinit var engine: EnforcementEngine
        val controlled = object : StateFlow<StorageRecoveryState> by raw {
            override val value: StorageRecoveryState
                get() {
                    val captured = raw.value
                    if (!captured.required && armRead.get() && inOwnReload() &&
                        skipReloadReads.getAndDecrement() <= 0 && armRead.compareAndSet(true, false)) {
                        reads.value = Result.failure(IOException("synthetic second failure"))
                        runBlocking {
                            withTimeout(10_000) {
                                raw.first { it.required }
                                secondFailurePublished.complete(Unit)
                                resumePublication.await()
                            }
                        }
                    }
                    return captured
                }

            override suspend fun collect(collector: FlowCollector<StorageRecoveryState>): Nothing {
                raw.collect { recovery ->
                    delivered.add(recovery)
                    collector.emit(recovery)
                    if (!recovery.required && recovery.generation == 0L) {
                        initialRecoveryDelivered.complete(Unit)
                    }
                    if (recovery.required && recovery.generation >= 2) {
                        newFailureDelivered.complete(recovery.generation)
                    }
                    if (recovery.required && !collectorPaused.isCompleted) {
                        collectorPaused.complete(Unit)
                        resumeCollector.await()
                    }
                }
            }
        }
        LocalDataStore::class.java.getDeclaredField("storageRecoveryState").apply {
            isAccessible = true
            set(local, controlled)
        }
        val profiles = FakeProfileRepository()
        val profile = Profile("synthetic", "initial", isActive = active,
            blockedPackages = setOf("invalid.synthetic.blocked"), activatedAtEpochMs = 1L)
        profiles.saveProfile(profile)
        val repository = object : ProfileRepository by profiles {
            override val enforcementSnapshotFlow = flow {
                profiles.enforcementSnapshotFlow.collect {
                    val target = markSnapshot.compareAndSet(true, false)
                    emit(it)
                    if (target) snapshotPublished.complete(Unit)
                }
            }
            override suspend fun readEnforcementSnapshot(): EnforcementSnapshot =
                profiles.readEnforcementSnapshot().also { firstRead.complete(Unit) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> snapshotPublished.completeExceptionally(failure) })
        engine = EnforcementEngine(repository, local, scope)
        engine.registerExemptPackage("invalid.synthetic.launcher")
        try {
            withTimeout(10_000) {
                firstRead.await()
                readReady.await()
                initialRecoveryDelivered.await()
                assertFalse(engine.requestEnd("not-active", EndRequest.Manual))
                reads.value = Result.failure(IOException("synthetic first failure"))
                collectorPaused.await()
                assertTrue(engine.enforcementState.value.storageRecoveryRequired)
                reads.value = Result.success(emptyPreferences())
                local.retryReadingPersistedState()
                raw.first { !it.required }
                assertFalse(local.recoveryRequiredFlow.value)
                assertTrue("another reader's success is not this engine's acknowledged reload",
                    engine.enforcementState.value.storageRecoveryRequired)
                // The observer is still busy after its first failure. A Boolean would conflate
                // away this transition; the versioned source must retain the newer episode.
                armRead.set(true)
                markSnapshot.set(true)
                profiles.saveProfile(profile.copy(name = "prepared retry"))
                secondFailurePublished.await()
                assertTrue(raw.value.required)
                assertEquals(listOf(false, true), delivered.map { it.required })
                if (pauseBeforePublication) engine.pauseRecoveryLockdown()
                resumePublication.complete(Unit)
                snapshotPublished.await()
                assertTrue("current failed storage must dominate an ABA snapshot publication",
                    engine.enforcementState.value.storageRecoveryRequired)
                assertEquals("an old successful reload cannot withdraw a newer deliberate pause",
                    pauseBeforePublication, engine.enforcementState.value.recoveryLockdownPaused)
                assertEquals(!pauseBeforePublication, engine.isPackageBlocked("invalid.synthetic.unlisted"))
                assertEquals(active, engine.enforcementState.value.isBlockingActive)
                assertFalse(engine.isPackageBlocked("com.android.phone"))
                assertFalse(engine.isPackageBlocked("invalid.synthetic.launcher"))
                engine.pauseRecoveryLockdown()
                assertFalse(engine.isPackageBlocked("invalid.synthetic.unlisted"))
                assertEquals(active, engine.isPackageBlocked("invalid.synthetic.blocked"))
                resumeCollector.complete(Unit)
                assertTrue(newFailureDelivered.await() >= 2)
                assertEquals(2L, raw.value.episode)
                reads.value = Result.success(emptyPreferences())
                local.retryReadingPersistedState()
                engine.enforcementState.first { !it.storageRecoveryRequired }
                assertFalse("own reload re-arms the deliberately paused lockdown",
                    engine.enforcementState.value.recoveryLockdownPaused)
                assertEquals(active, engine.isPackageBlocked("invalid.synthetic.blocked"))
            }
        } finally {
            resumePublication.complete(Unit)
            resumeCollector.complete(Unit)
            engine.stop()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private data class Observation(
        val state: EnforcementState,
        val widenedBlocking: Boolean,
        val exemptions: List<Boolean>
    )

    private suspend fun verifyPublication(active: Boolean, alreadyFailed: Boolean = false) {
        val reads = MutableStateFlow<Result<Preferences>>(Result.success(emptyPreferences()))
        val initialDataPublished = CompletableDeferred<Unit>()
        val store = object : DataStore<Preferences> {
            override val data = flow {
                reads.collect {
                    emit(it.getOrThrow())
                    initialDataPublished.complete(Unit)
                }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("synthetic storage is read-only")
        }
        val local = LocalDataStore(store)
        val underlyingFlag = local.storageRecoveryState
        val armPublication = AtomicBoolean(false)
        val skipReloadReads = AtomicInteger(if (alreadyFailed) 0 else 1)
        val failurePublished = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val firstSnapshotRead = CompletableDeferred<Unit>()
        val pauseNextRead = AtomicBoolean(false)
        val retryReadPrepared = CompletableDeferred<Unit>()
        val releaseRetryRead = CompletableDeferred<Unit>()
        val probeNextRead = AtomicBoolean(false)
        val observed = CompletableDeferred<Observation>()
        val exemptions = listOf(
            "com.android.phone", "com.android.telecom", "com.android.emergency",
            "com.google.android.dialer", "com.android.systemui", "websnag.elopenmike.com",
            "invalid.synthetic.launcher"
        )
        lateinit var engine: EnforcementEngine

        // A test-local read decorator simulates preemption immediately after value capture.
        // Production LocalDataStore handles the IOException and the real engine observer publishes
        // the failure. No engine state is injected and no production scheduling hook is added.
        val interruptedRead = object : StateFlow<StorageRecoveryState> by underlyingFlag {
            override val value: StorageRecoveryState
                get() {
                    val captured = underlyingFlag.value
                    if (!captured.required && armPublication.get() && inOwnReload() &&
                        skipReloadReads.getAndDecrement() <= 0 && armPublication.compareAndSet(true, false)) {
                        reads.value = Result.failure(IOException("synthetic concurrent read failure"))
                        runBlocking {
                            withTimeout(10_000) {
                                underlyingFlag.first { it.required }
                                engine.enforcementState.first { it.storageRecoveryRequired }
                                failurePublished.complete(Unit)
                                releaseSnapshot.await()
                            }
                        }
                    }
                    return captured
                }
        }
        LocalDataStore::class.java.getDeclaredField("storageRecoveryState").apply {
            isAccessible = true
            set(local, interruptedRead)
        }
        assertSame(interruptedRead, local.storageRecoveryState)

        val profiles = FakeProfileRepository()
        val profile = Profile("synthetic", "initial", isActive = active,
            blockedPackages = setOf("invalid.synthetic.blocked"), activatedAtEpochMs = 1L)
        profiles.saveProfile(profile)
        val repository = object : ProfileRepository by profiles {
            override suspend fun readEnforcementSnapshot(): EnforcementSnapshot {
                val current = profiles.readEnforcementSnapshot()
                firstSnapshotRead.complete(Unit)
                if (pauseNextRead.compareAndSet(true, false)) {
                    retryReadPrepared.complete(Unit)
                    releaseRetryRead.await()
                }
                if (probeNextRead.compareAndSet(true, false)) {
                    // This subsequent read is serialized after the prepared snapshot was published,
                    // but before another snapshot can repair the accidentally overwritten flag.
                    observed.complete(Observation(
                        engine.enforcementState.value,
                        engine.isPackageBlocked("invalid.synthetic.unlisted"),
                        exemptions.map(engine::isPackageBlocked)
                    ))
                }
                return current
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> observed.completeExceptionally(failure) })
        engine = EnforcementEngine(repository, local, scope)
        engine.registerExemptPackage("invalid.synthetic.launcher")
        try {
            withTimeout(10_000) {
                firstSnapshotRead.await()
                initialDataPublished.await()
                // Acquiring the public command path waits for initial snapshot application.
                assertFalse(engine.requestEnd("not-active", EndRequest.Manual))
                if (alreadyFailed) {
                    reads.value = Result.failure(IOException("synthetic first failure"))
                    underlyingFlag.first { it.required }
                    engine.enforcementState.first { it.storageRecoveryRequired }
                }
                if (alreadyFailed) {
                    pauseNextRead.set(true)
                    reads.value = Result.success(emptyPreferences())
                    local.retryReadingPersistedState()
                    retryReadPrepared.await()
                    assertTrue("retry starts with the engine's prior lockdown still active",
                        engine.enforcementState.value.storageRecoveryRequired)
                    armPublication.set(true)
                    releaseRetryRead.complete(Unit)
                } else {
                    armPublication.set(true)
                    profiles.saveProfile(profile.copy(name = "prepared"))
                }
                failurePublished.await()
                assertTrue(underlyingFlag.value.required)
                probeNextRead.set(true)
                profiles.saveProfile(profile.copy(name = "probe"))
                releaseSnapshot.complete(Unit)
                releaseRetryRead.complete(Unit)
                val result = observed.await()
                assertTrue("snapshot publication must retain the newer storage failure", result.state.storageRecoveryRequired)
                assertTrue("failure must still widen blocking outside the loaded policy", result.widenedBlocking)
                assertTrue("system exemptions remain reachable", result.exemptions.none { it })
                assertEquals(active, result.state.isBlockingActive)
                assertEquals(if (active) profile.id else null, result.state.activeProfile?.id)
                engine.pauseRecoveryLockdown()
                assertFalse(engine.isPackageBlocked("invalid.synthetic.unlisted"))
                assertEquals("pausing only releases extra blocking, never a loaded session",
                    active, engine.isPackageBlocked("invalid.synthetic.blocked"))
            }
        } finally {
            releaseSnapshot.complete(Unit)
            engine.stop()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
