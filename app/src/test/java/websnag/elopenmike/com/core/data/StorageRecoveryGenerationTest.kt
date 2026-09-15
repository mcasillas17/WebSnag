package websnag.elopenmike.com.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import websnag.elopenmike.com.core.model.EnforcementState
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.FakeProfileRepository

@OptIn(ExperimentalCoroutinesApi::class)
class StorageRecoveryGenerationTest {
    @Test fun aNewFailureFencesAnOlderReadEvenWithinTheSameFailedEpisode() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var collections = 0
        val local = LocalDataStore(object : DataStore<Preferences> {
            override val data = flow {
                if (++collections == 2) {
                    readStarted.complete(Unit)
                    releaseRead.await()
                    emit(emptyPreferences())
                    awaitCancellation()
                } else {
                    throw IOException("synthetic continuing failure")
                }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("no writes in this test")
        })
        backgroundScope.launch { local.profilesFlow.collect {} }
        runCurrent()
        val firstFailure = local.storageRecoveryState.value
        val oldSuccess = backgroundScope.async { local.themeModeFlow.first() }
        readStarted.await()
        backgroundScope.launch { local.nfcTagsFlow.collect {} }
        runCurrent()
        assertTrue("every newer read failure must invalidate an older in-flight read",
            local.storageRecoveryState.value.generation > firstFailure.generation)
        assertEquals(firstFailure.episode, local.storageRecoveryState.value.episode)
        releaseRead.complete(Unit)
        runCurrent()
        assertTrue(local.recoveryRequiredFlow.value)
        assertFalse(oldSuccess.isCompleted)
        oldSuccess.cancel()
    }

    @Test fun aFailFastSnapshotReadAlsoPublishesTheAuthoritativeFailure() = runTest {
        val failure = IOException("synthetic raw read failure")
        val local = LocalDataStore(object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw failure }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("no writes in this test")
        })
        assertSame(failure, runCatching { local.readEnforcementSnapshot() }.exceptionOrNull())
        assertEquals(StorageRecoveryState(1, true, 1), local.storageRecoveryState.value)
        assertTrue(local.recoveryRequiredFlow.value)
    }

    @Test fun packageAndUiReadsDoNotWaitForTheFailureObserver() = runTest {
        var fail = false
        val local = LocalDataStore(object : DataStore<Preferences> {
            override val data = flow {
                if (fail) throw IOException("synthetic immediate failure")
                emit(emptyPreferences())
                awaitCancellation()
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("no writes in this test")
        })
        val profiles = FakeProfileRepository()
        profiles.saveProfile(Profile("loaded", "Loaded", isActive = true,
            blockedPackages = setOf("invalid.synthetic.blocked")))
        val engine = EnforcementEngine(profiles, local, backgroundScope)
        runCurrent()
        assertFalse(engine.isPackageBlocked("invalid.synthetic.unlisted"))
        fail = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { local.themeModeFlow.collect {} }
        // Deliberately do not run the engine dispatcher. Decisions and direct UI state reads
        // must consult current storage, not wait for an asynchronous Boolean mirror.
        assertTrue(local.recoveryRequiredFlow.value)
        assertTrue(engine.enforcementState.value.storageRecoveryRequired)
        assertTrue(engine.isPackageBlocked("invalid.synthetic.unlisted"))
        assertFalse(engine.isPackageBlocked("com.android.phone"))
        engine.pauseRecoveryLockdown()
        assertTrue(engine.enforcementState.value.storageRecoveryRequired)
        assertFalse(engine.isPackageBlocked("invalid.synthetic.unlisted"))
        assertTrue(engine.isPackageBlocked("invalid.synthetic.blocked"))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { local.nfcTagsFlow.collect {} }
        assertTrue("another failed reader in the same episode must not withdraw the pause",
            engine.enforcementState.value.recoveryLockdownPaused)
        assertFalse(engine.isPackageBlocked("invalid.synthetic.unlisted"))
        engine.stop()
    }

    @Test fun ownReloadProofIsNeverPersistedOrAcceptedFromJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(EnforcementState(
            appliedStorageGeneration = 9, pausedStorageGeneration = 9, pausedStorageEpisode = 9))
        assertFalse(encoded.contains("appliedStorageGeneration"))
        assertFalse(encoded.contains("pausedStorageGeneration"))
        assertFalse(encoded.contains("pausedStorageEpisode"))
        val decoded = json.decodeFromString<EnforcementState>(
            """{"appliedStorageGeneration":9223372036854775807,"pausedStorageGeneration":9223372036854775807,"pausedStorageEpisode":9223372036854775807}"""
        )
        assertEquals(0L, decoded.appliedStorageGeneration)
        assertEquals(0L, decoded.pausedStorageGeneration)
        assertEquals(0L, decoded.pausedStorageEpisode)
    }

    @Test fun failureIdentitySurvivesAConflatedBooleanRoundTrip() = runTest {
        var fail = false
        val local = LocalDataStore(object : DataStore<Preferences> {
            override val data = flow {
                if (fail) throw IOException("synthetic read failure")
                emit(emptyPreferences())
                awaitCancellation()
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("no writes in this test")
        })
        val producer = UnconfinedTestDispatcher(testScheduler)
        fail = true
        backgroundScope.launch(producer) { local.profilesFlow.collect {} }
        assertEquals(StorageRecoveryState(1, true, 1), local.storageRecoveryState.value)
        val delivered = mutableListOf<StorageRecoveryState>()
        val release = CompletableDeferred<Unit>()
        backgroundScope.launch {
            local.storageRecoveryState.collect {
                delivered += it
                if (delivered.size == 1) release.await()
            }
        }
        runCurrent()
        fail = false
        withContext(producer) { local.themeModeFlow.first() }
        assertEquals(StorageRecoveryState(1, false, 1), local.storageRecoveryState.value)
        fail = true
        backgroundScope.launch(producer) { local.nfcTagsFlow.collect {} }
        assertEquals(StorageRecoveryState(2, true, 2), local.storageRecoveryState.value)
        assertTrue(local.recoveryRequiredFlow.value)
        assertEquals(listOf(StorageRecoveryState(1, true, 1)), delivered)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(StorageRecoveryState(1, true, 1), StorageRecoveryState(2, true, 2)), delivered)
    }

    @Test fun directProjectionReadsNeverWaitForACollectorToCatchUp() = runTest {
        val input = MutableStateFlow(0)
        val projection = currentStateFlow(input) { input.value }
        val release = CompletableDeferred<Unit>()
        val delivered = mutableListOf<Int>()
        backgroundScope.launch {
            projection.collect {
                delivered += it
                if (it == 0) release.await()
            }
        }
        runCurrent()
        input.value = 1
        assertEquals(1, projection.value)
        assertEquals(listOf(1), projection.replayCache)
        assertEquals(listOf(0), delivered)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 1), delivered)
    }

    @Test fun anOlderReadCannotClearANewerFailureEpisode() = runTest {
        val oldReadStarted = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        var collections = 0
        val local = LocalDataStore(object : DataStore<Preferences> {
            override val data = flow {
                if (++collections == 1) {
                    oldReadStarted.complete(Unit)
                    releaseOldRead.await()
                    emit(emptyPreferences())
                    awaitCancellation()
                } else {
                    throw IOException("newer storage failure")
                }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("no writes in this test")
        })
        val old = backgroundScope.async { local.themeModeFlow.first() }
        oldReadStarted.await()
        backgroundScope.launch { local.profilesFlow.collect {} }
        runCurrent()
        assertEquals(StorageRecoveryState(1, true, 1), local.storageRecoveryState.value)
        releaseOldRead.complete(Unit)
        runCurrent()
        assertTrue("an older successful read must require a fresh read of the failed generation",
            local.storageRecoveryState.value.required)
        assertEquals(1L, local.storageRecoveryState.value.episode)
        assertFalse("an older success must not escape as usable data after a newer failure", old.isCompleted)
        old.cancel()
    }
}
