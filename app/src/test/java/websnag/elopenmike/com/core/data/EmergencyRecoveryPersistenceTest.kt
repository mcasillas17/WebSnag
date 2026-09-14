package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.enforcement.EmergencyClock
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.model.EmergencyRecovery
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyRecoveryPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Harness(val test: TestScope) {
        val file = temporary.newFolder().resolve("session.preferences_pb")
        var failReads = false
        var failWrites = false
        var hangWrites = false
        var attemptedWrites = 0
        var beforeWrite: (suspend () -> Unit)? = null
        lateinit var physical: DataStore<Preferences>
        var storageScope = CoroutineScope(test.backgroundScope.coroutineContext + SupervisorJob())
        var store: DataStore<Preferences> = openStore()
        var local = LocalDataStore(store)
        var repository = DefaultProfileRepository(local)
        var elapsed = 10_000L
        var boot: String? = "boot-a"
        var engine = newEngine()

        fun openStore(): DataStore<Preferences> {
            physical = PreferenceDataStoreFactory.create(scope = storageScope) { file }
            val backing = physical
            return object : DataStore<Preferences> {
                override val data = backing.data.map {
                    if (failReads) throw java.io.IOException("synthetic read failure")
                    it
                }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    attemptedWrites++
                    beforeWrite?.also { beforeWrite = null }?.invoke()
                    if (failWrites) throw java.io.IOException("synthetic write failure")
                    if (hangWrites) awaitCancellation()
                    return backing.updateData(transform)
                }
            }
        }

        fun newEngine() = EnforcementEngine(repository, local, test.backgroundScope,
            EmergencyClock({ elapsed }, { boot }))

        suspend fun activate(condition: UnlockCondition.RequireNfcTag = UnlockCondition.RequireNfcTag(emergencyCooldownMinutes = 1)) {
            repository.saveProfile(Profile("synthetic", "Synthetic",
                blockedPackages = setOf("invalid.synthetic.blocked"), unlockCondition = condition))
            repository.setActiveProfile("synthetic")
            test.runCurrent()
        }

        suspend fun reopen() {
            engine.stop()
            storageScope.coroutineContext[Job]!!.cancelAndJoin()
            storageScope = CoroutineScope(test.backgroundScope.coroutineContext + SupervisorJob())
            store = openStore()
            local = LocalDataStore(store)
            repository = DefaultProfileRepository(local)
            engine = newEngine()
            test.runCurrent()
        }

        suspend fun close() {
            engine.stop()
            storageScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun exactBoundaryCommitsOnceToRealDataStore() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            var endings = 0
            backgroundScope.launch { h.engine.endEvents.collect { endings++ } }
            assertTrue(h.engine.startEmergencyUnlock(true))
            runCurrent()
            h.elapsed += 59_999
            advanceTimeBy(1_000); runCurrent()
            assertTrue(h.engine.enforcementState.value.isBlockingActive)
            assertEquals(1L, h.engine.enforcementState.value.remainingEmergencyMs)
            h.elapsed++
            advanceTimeBy(1); runCurrent()
            assertFalse(h.engine.enforcementState.value.isBlockingActive)
            assertNull(h.repository.readEnforcementSnapshot().activeProfile)
            assertNull(h.local.emergencyRecoveryFlow.first())
            assertEquals(1, endings)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, endings)
            h.reopen()
            assertFalse(h.engine.enforcementState.value.isBlockingActive)
        } finally { h.close() }
    }

    @Test fun sameBootReopenIgnoresPastAndFutureWallAnchors() = runTest {
        for (wall in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) {
            val h = Harness(this)
            try {
                h.activate()
                assertTrue(h.engine.startEmergencyUnlock(true))
                val request = h.local.emergencyRecoveryFlow.first()!!
                h.engine.stop()
                h.local.saveEmergencyRecovery(request.copy(startedAtEpochMs = wall))
                h.elapsed += 20_000
                h.reopen()
                assertEquals(40_000L, h.engine.enforcementState.value.remainingEmergencyMs)
                assertEquals(request.requestId, h.local.emergencyRecoveryFlow.first()!!.requestId)
                h.elapsed += 39_999
                advanceTimeBy(1_000); runCurrent()
                assertTrue(h.engine.enforcementState.value.isBlockingActive)
                h.elapsed++
                advanceTimeBy(1); runCurrent()
                assertNull(h.repository.readEnforcementSnapshot().activeProfile)
            } finally { h.close() }
        }

    }

    @Test fun liveWallMetadataJumpsDoNotChangeElapsedCountdown() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            assertTrue(h.engine.startEmergencyUnlock(true))
            h.elapsed += 20_000
            for (wall in listOf(Long.MAX_VALUE, Long.MIN_VALUE)) {
                val request = h.local.emergencyRecoveryFlow.first()!!
                h.local.saveEmergencyRecovery(request.copy(startedAtEpochMs = wall))
                runCurrent()
                assertEquals(40_000L, h.engine.enforcementState.value.remainingEmergencyMs)
                assertTrue(h.engine.enforcementState.value.isBlockingActive)
            }
        } finally { h.close() }
    }

    @Test fun rebootUnknownBootAndInvalidAnchorsRestartFullDuration() = runTest {
        val cases = listOf(
            "other-boot" to 0L, null to 0L, "" to 0L, "boot-a" to -1L,
            "boot-a" to Long.MAX_VALUE, "boot-a" to null
        )
        for ((boot, anchor) in cases) {
            val h = Harness(this)
            try {
                h.activate()
                assertTrue(h.engine.startEmergencyUnlock(true))
                val request = h.local.emergencyRecoveryFlow.first()!!
                h.engine.stop()
                h.local.saveEmergencyRecovery(request.copy(bootId = boot, startedAtElapsedMs = anchor))
                h.elapsed += 120_000
                h.reopen()
                val restarted = h.local.emergencyRecoveryFlow.first()!!
                assertNotEquals(request.requestId, restarted.requestId)
                assertEquals(h.elapsed, restarted.startedAtElapsedMs)
                assertEquals(60_000L, h.engine.enforcementState.value.remainingEmergencyMs)
                assertTrue(h.engine.enforcementState.value.isBlockingActive)
                // Unrelated preference emissions must not restart an unknown-boot timer.
                h.elapsed += 10_000
                h.local.setHistoryRetentionDays(30)
                advanceTimeBy(1_000); runCurrent()
                assertEquals(50_000L, h.engine.enforcementState.value.remainingEmergencyMs)
            } finally { h.close() }
        }
    }

    @Test fun everyEditorDurationAndLegacySeventeenMinutesAreSupported() = runTest {
        for (minutes in listOf(1, 5, 10, 17, Int.MAX_VALUE)) {
            val h = Harness(this)
            try {
                h.activate(UnlockCondition.RequireNfcTag(emergencyCooldownMinutes = minutes))
                assertTrue(h.engine.startEmergencyUnlock(true))
                assertEquals(minutes.toLong() * 60_000, h.local.emergencyRecoveryFlow.first()!!.durationMs)
                h.elapsed += minutes.toLong() * 60_000 - 1
                advanceTimeBy(1_000); runCurrent()
                assertTrue(h.engine.enforcementState.value.isBlockingActive)
                h.elapsed++
                advanceTimeBy(1); runCurrent()
                assertNull(h.repository.readEnforcementSnapshot().activeProfile)
            } finally { h.close() }
        }
    }

    @Test fun invalidPersistedDurationRestartsConfiguredWaitWithoutOverflow() = runTest {
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
            val h = Harness(this)
            try {
                h.activate()
                assertTrue(h.engine.startEmergencyUnlock(true))
                val request = h.local.emergencyRecoveryFlow.first()!!
                h.engine.stop()
                h.local.saveEmergencyRecovery(request.copy(durationMs = duration))
                h.reopen()
                assertEquals(60_000L, h.engine.enforcementState.value.remainingEmergencyMs)
                assertNotEquals(request.requestId, h.local.emergencyRecoveryFlow.first()!!.requestId)
                assertTrue(h.engine.enforcementState.value.isBlockingActive)
            } finally { h.close() }
        }
    }

    @Test fun phraseAndEmergencyPolicyMatrixIsEnforcedAtStartAndCompletion() = runTest {
        for (enabled in listOf(false, true)) for (required in listOf(false, true)) for (confirmed in listOf(false, true)) {
            val h = Harness(this)
            try {
                h.activate(UnlockCondition.RequireNfcTag(
                    emergencyCooldownMinutes = 1, allowEmergencyUnlock = enabled, requireIntentionPhrase = required))
                val accepted = enabled && (!required || confirmed)
                assertEquals(accepted, h.engine.startEmergencyUnlock(confirmed))
                h.elapsed += 60_000
                advanceTimeBy(1_000); runCurrent()
                assertEquals(!accepted, h.engine.enforcementState.value.isBlockingActive)
                assertFalse(h.engine.requestEnd("synthetic", EndRequest.Emergency(true, true)))
            } finally { h.close() }
        }
    }

    @Test fun persistedCancelRestartDuplicateAndStaleCancelAreOrdered() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            assertTrue(h.engine.startEmergencyUnlock(true))
            val first = h.local.emergencyRecoveryFlow.first()!!
            h.elapsed += 10_000
            assertTrue(h.engine.startEmergencyUnlock(true))
            assertEquals(first, h.local.emergencyRecoveryFlow.first())
            assertTrue(h.engine.cancelEmergencyUnlock(first.requestId!!))
            assertNull(h.local.emergencyRecoveryFlow.first())
            h.reopen()
            assertFalse(h.engine.enforcementState.value.emergencyCooldownActive)
            assertTrue(h.engine.startEmergencyUnlock(true))
            val second = h.local.emergencyRecoveryFlow.first()!!
            assertNotEquals(first.requestId, second.requestId)
            assertFalse(h.engine.cancelEmergencyUnlock(first.requestId))
            assertEquals(second, h.local.emergencyRecoveryFlow.first())
            assertFalse(h.engine.startEmergencyUnlock(true, first.sessionId, first.requestId))
        } finally { h.close() }
    }

    @Test fun sameProfileReactivationCannotInheritPreviousTimerOrStaleCallbacks() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            assertTrue(h.engine.startEmergencyUnlock(true))
            val old = h.local.emergencyRecoveryFlow.first()!!
            h.repository.setActiveProfile("synthetic")
            runCurrent()
            assertNotEquals(old.sessionId, h.repository.readEnforcementSnapshot().activeProfile!!.sessionId)
            assertFalse(h.engine.startEmergencyUnlock(true, old.sessionId, old.requestId))
            assertNull(h.local.emergencyRecoveryFlow.first())
            // Even an old persisted request reintroduced later cannot end the new session.
            h.local.saveEmergencyRecovery(old)
            runCurrent()
            h.elapsed += 120_000
            advanceTimeBy(1_000); runCurrent()
            assertTrue(h.engine.enforcementState.value.isBlockingActive)
            assertNotNull(h.engine.enforcementState.value.emergencyRecoveryError)
        } finally { h.close() }
    }

    @Test fun completionRechecksChangedPolicyBeforeCommitting() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            assertTrue(h.engine.startEmergencyUnlock(true))
            val active = h.repository.readEnforcementSnapshot().activeProfile!!
            h.local.saveProfiles(listOf(active.copy(unlockCondition =
                UnlockCondition.RequireNfcTag(allowEmergencyUnlock = false))))
            runCurrent()
            h.elapsed += 120_000
            advanceTimeBy(1_000); runCurrent()
            assertNotNull(h.repository.readEnforcementSnapshot().activeProfile)
            assertNotNull(h.local.emergencyRecoveryFlow.first())
        } finally { h.close() }
    }

    @Test fun increasedPolicyDurationCannotUseAShorterRunningRequest() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            assertTrue(h.engine.startEmergencyUnlock(true))
            val active = h.repository.readEnforcementSnapshot().activeProfile!!
            h.elapsed += 30_000
            h.local.saveProfiles(listOf(active.copy(unlockCondition =
                UnlockCondition.RequireNfcTag(emergencyCooldownMinutes = 5))))
            runCurrent()
            assertEquals(300_000L, h.engine.enforcementState.value.remainingEmergencyMs)
            h.elapsed += 60_000
            advanceTimeBy(1_000); runCurrent()
            assertNotNull(h.repository.readEnforcementSnapshot().activeProfile)
        } finally { h.close() }
    }

    @Test fun unconfirmedLegacyRecoveryCanBeCancelledButCannotComplete() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            h.local.saveEmergencyRecovery(EmergencyRecovery("synthetic", 0, 60_000, false))
            runCurrent()
            val request = h.local.emergencyRecoveryFlow.first()!!
            assertNotNull("even a rejected legacy request needs an identity for a deliberate cancel", request.requestId)
            assertFalse(h.engine.enforcementState.value.emergencyCooldownActive)
            assertNotNull(h.engine.enforcementState.value.emergencyRecoveryError)
            assertTrue(h.engine.cancelEmergencyUnlock(request.requestId!!))
            assertTrue(h.engine.startEmergencyUnlock(true))
        } finally { h.close() }
    }

    @Test fun failedAndTimedOutWritesNeverReportSuccessOrReplay() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            val before = h.physical.data.first()
            h.failWrites = true
            assertFalse(h.engine.startEmergencyUnlock(true))
            assertEquals(before, h.physical.data.first())
            assertNotNull(h.engine.enforcementState.value.emergencyRecoveryError)
            h.failWrites = false
            h.hangWrites = true
            assertFalse(h.engine.startEmergencyUnlock(true))
            h.hangWrites = false
            h.local.retryReadingPersistedState()
            runCurrent()
            assertEquals(before, h.physical.data.first())
            assertNull(h.local.emergencyRecoveryFlow.first())

            assertTrue(h.engine.startEmergencyUnlock(true))
            val request = h.local.emergencyRecoveryFlow.first()!!
            h.failWrites = true
            assertFalse(h.engine.cancelEmergencyUnlock(request.requestId!!))
            h.elapsed += 60_000
            advanceTimeBy(1_000); runCurrent()
            assertTrue(h.engine.enforcementState.value.isBlockingActive)
            assertEquals(request, h.local.emergencyRecoveryFlow.first())
            assertNotNull(h.engine.enforcementState.value.emergencyRecoveryError)
            h.failWrites = false
            assertTrue(h.engine.cancelEmergencyUnlock(request.requestId))
            h.reopen()
            assertNotNull(h.repository.readEnforcementSnapshot().activeProfile)
            assertNull(h.local.emergencyRecoveryFlow.first())
        } finally { h.close() }
    }

    @Test fun storageLockdownPauseCannotUnlockLoadedPolicyOrReplayRejectedCommands() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            assertTrue(h.engine.startEmergencyUnlock(true))
            val request = h.local.emergencyRecoveryFlow.first()!!
            h.failReads = true
            h.physical.edit { it[stringPreferencesKey("synthetic-trigger")] = "read" }
            runCurrent()
            assertTrue(h.engine.enforcementState.value.storageRecoveryRequired)
            val before = h.physical.data.first()
            assertFalse(h.engine.startEmergencyUnlock(true))
            assertFalse(h.engine.cancelEmergencyUnlock(request.requestId!!))
            assertFalse(h.engine.requestEnd("synthetic", EndRequest.ScheduleEnded))
            h.engine.pauseRecoveryLockdown()
            assertTrue(h.engine.isPackageBlocked("invalid.synthetic.blocked"))
            assertFalse(h.engine.isPackageBlocked("com.android.phone"))
            assertFalse(h.engine.isPackageBlocked("com.google.android.dialer"))
            h.elapsed += 30_000
            advanceTimeBy(1_000); runCurrent()
            assertEquals(before, h.physical.data.first())
            h.failReads = false
            h.local.retryReadingPersistedState()
            runCurrent()
            assertFalse(h.engine.enforcementState.value.storageRecoveryRequired)
            assertFalse(h.engine.enforcementState.value.recoveryLockdownPaused)
            assertEquals(request, h.local.emergencyRecoveryFlow.first())
            assertTrue(h.engine.enforcementState.value.isBlockingActive)
        } finally { h.close() }
    }

    @Test fun profileEditRacingActivationCannotOverwriteTheDurableSession() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            assertTrue(h.engine.requestEnd("synthetic", EndRequest.ScheduleEnded))
            val inactive = h.repository.getProfileById("synthetic")!!
            h.beforeWrite = { h.repository.setActiveProfile("synthetic") }
            assertTrue(runCatching { h.repository.saveProfile(inactive.copy(name = "Stale editor")) }.isFailure)
            val active = h.repository.readEnforcementSnapshot().activeProfile!!
            assertNotNull(active.sessionId)
            assertEquals("Synthetic", active.name)
        } finally { h.close() }
    }

    @Test fun concurrentCancelAndRestartCannotLeaveAQueuedCancelOverTheNewRequest() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            assertTrue(h.engine.startEmergencyUnlock(true))
            val old = h.local.emergencyRecoveryFlow.first()!!
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.beforeWrite = { writing.complete(Unit); release.await() }
            val cancel = async { h.engine.cancelEmergencyUnlock(old.requestId!!) }
            writing.await()
            val start = async { h.engine.startEmergencyUnlock(true, old.sessionId, previousRequestId = null) }
            runCurrent()
            assertFalse(start.isCompleted)
            release.complete(Unit)
            assertTrue(cancel.await())
            assertTrue(start.await())
            val replacement = h.local.emergencyRecoveryFlow.first()!!
            assertNotEquals(old.requestId, replacement.requestId)
            assertFalse(h.engine.cancelEmergencyUnlock(old.requestId!!))
            assertEquals(replacement, h.local.emergencyRecoveryFlow.first())
        } finally { h.close() }
    }

    @Test fun startAndCompletionCompareTheCurrentPersistedSessionInsideTheWrite() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            h.beforeWrite = { h.repository.setActiveProfile("synthetic") }
            assertFalse(h.engine.startEmergencyUnlock(true))
            runCurrent()
            assertNull(h.local.emergencyRecoveryFlow.first())
            assertTrue(h.engine.startEmergencyUnlock(true))
            val old = h.local.emergencyRecoveryFlow.first()!!
            h.beforeWrite = { h.repository.setActiveProfile("synthetic") }
            h.elapsed += 60_000
            advanceTimeBy(1_000); runCurrent()
            val active = h.repository.readEnforcementSnapshot().activeProfile!!
            assertNotEquals(old.sessionId, active.sessionId)
            assertTrue(h.engine.enforcementState.value.isBlockingActive)
        } finally { h.close() }
    }

    @Test fun unknownCurrentBootRestartsOnReopenButNotUnrelatedEmissions() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            h.boot = null
            assertTrue(h.engine.startEmergencyUnlock(true))
            val old = h.local.emergencyRecoveryFlow.first()!!
            h.elapsed += 20_000
            h.local.setHistoryRetentionDays(30)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(40_000L, h.engine.enforcementState.value.remainingEmergencyMs)
            h.reopen()
            assertEquals(60_000L, h.engine.enforcementState.value.remainingEmergencyMs)
            assertNotEquals(old.requestId, h.local.emergencyRecoveryFlow.first()!!.requestId)
        } finally { h.close() }
    }

    @Test fun storageFailureMustFailClosedEvenWhileACommandIsWaitingForDisk() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            h.hangWrites = true
            val start = async { h.engine.startEmergencyUnlock(true) }
            runCurrent()
            assertFalse(start.isCompleted)
            h.failReads = true
            h.physical.edit { it[stringPreferencesKey("synthetic-trigger")] = "failure-during-write" }
            runCurrent()
            assertTrue(h.local.recoveryRequiredFlow.value)
            assertTrue("failure notification cannot wait behind an in-flight write",
                h.engine.isPackageBlocked("invalid.synthetic.unlisted"))
            start.cancelAndJoin()
        } finally { h.close() }
    }

    @Test fun legacyWallClockDowntimeCannotCompleteRecovery() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("legacy.preferences_pb")
        }
        val local = LocalDataStore(store)
        val repository = DefaultProfileRepository(local)
        repository.saveProfile(Profile("synthetic", "Synthetic",
            unlockCondition = UnlockCondition.RequireNfcTag(emergencyCooldownMinutes = 17)))
        repository.setActiveProfile("synthetic")
        local.saveEmergencyRecovery(EmergencyRecovery("synthetic", 0, 17 * 60_000L, true))
        val engine = EnforcementEngine(repository, local, backgroundScope,
            emergencyClock = websnag.elopenmike.com.core.enforcement.EmergencyClock({ testScheduler.currentTime }, { "boot-a" }))
        runCurrent()
        assertTrue("wall downtime is not trusted elapsed progress", engine.enforcementState.value.isBlockingActive)
        assertTrue(engine.enforcementState.value.emergencyCooldownActive)
        assertEquals(17 * 60_000L, engine.enforcementState.value.remainingEmergencyMs)
    }

    @Test fun completedWaitRetriesWriteFailureWithoutRestartOrBusyLoop() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            var endings = 0
            backgroundScope.launch { h.engine.endEvents.collect { endings++ } }
            assertTrue(h.engine.startEmergencyUnlock(true))
            val original = h.local.emergencyRecoveryFlow.first()!!
            h.failWrites = true
            h.elapsed += 60_000
            advanceTimeBy(1_000); runCurrent()
            val attempts = h.attemptedWrites
            assertEquals(original, h.local.emergencyRecoveryFlow.first())
            advanceTimeBy(4_999); runCurrent()
            assertEquals("write retries must not use the zero-countdown 1ms path", attempts, h.attemptedWrites)
            h.failWrites = false
            advanceTimeBy(1); runCurrent()
            assertFalse("a completed wait must finish after storage recovers without cancel/restart",
                h.engine.enforcementState.value.isBlockingActive)
            assertNull(h.local.emergencyRecoveryFlow.first())
            assertEquals(1, endings)
            advanceTimeBy(10_000); runCurrent()
            assertEquals(1, endings)
        } finally { h.close() }
    }

    @Test fun repairQueuedDuringBoundaryFailureCannotStrandRecoveryOwnership() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            var endings = 0
            backgroundScope.launch { h.engine.endEvents.collect { endings++ } }
            assertTrue(h.engine.startEmergencyUnlock(true))
            val original = h.local.emergencyRecoveryFlow.first()!!
            val writeEntered = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            h.beforeWrite = {
                writeEntered.complete(Unit)
                releaseWrite.await()
                throw java.io.IOException("synthetic boundary failure")
            }
            h.elapsed += 60_000
            advanceTimeBy(1_000); runCurrent()
            writeEntered.await()
            h.failReads = true
            h.physical.edit { it[stringPreferencesKey("synthetic-failure")] = "read" }
            runCurrent()
            assertTrue(h.local.recoveryRequiredFlow.value)
            h.failReads = false
            h.local.retryReadingPersistedState()
            // Both repaired-state observers now wait behind the timer's suspended write.
            runCurrent()
            releaseWrite.complete(Unit)
            runCurrent()
            assertEquals(original, h.local.emergencyRecoveryFlow.first())
            advanceTimeBy(5_000); runCurrent()
            assertFalse("repair cannot consume the last emission and leave no timer",
                h.engine.enforcementState.value.isBlockingActive)
            assertEquals(1, endings)
        } finally { h.close() }
    }

    @Test fun completionRetryRechecksCancelReactivationAndPolicy() = runTest {
        for (change in listOf("cancel", "reactivate", "policy")) {
            val h = Harness(this)
            try {
                h.activate()
                assertTrue(h.engine.startEmergencyUnlock(true))
                val original = h.local.emergencyRecoveryFlow.first()!!
                h.failWrites = true
                h.elapsed += 60_000
                advanceTimeBy(1_000); runCurrent()
                h.failWrites = false
                when (change) {
                    "cancel" -> assertTrue(h.engine.cancelEmergencyUnlock(original.requestId!!))
                    "reactivate" -> h.repository.setActiveProfile("synthetic")
                    else -> {
                        val active = h.repository.readEnforcementSnapshot().activeProfile!!
                        h.local.saveProfiles(listOf(active.copy(unlockCondition =
                            UnlockCondition.RequireNfcTag(allowEmergencyUnlock = false))))
                    }
                }
                runCurrent()
                advanceTimeBy(10_000); runCurrent()
                assertTrue("a stale retry cannot end a changed session/request/policy",
                    h.engine.enforcementState.value.isBlockingActive)
            } finally { h.close() }
        }
    }

    @Test fun queuedActivationCannotReplayAfterFailureEpisodes() = runTest {
        val h = Harness(this)
        try {
            h.activate()
            h.repository.saveProfile(Profile("another", "Another", unlockCondition = UnlockCondition.ManualOnly))
            h.engine.stop()
            h.engine = EnforcementEngine(h.repository, h.local, backgroundScope,
                EmergencyClock({ h.elapsed }, { h.boot }), hasEnrolledNfcTag = { true })
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.beforeWrite = { entered.complete(Unit); release.await() }
            val start = async { h.engine.startEmergencyUnlock(true) }
            entered.await()
            val activation = async { h.engine.tryActivateProfile("another") }
            runCurrent()
            assertFalse(activation.isCompleted)
            val producer = UnconfinedTestDispatcher(testScheduler)
            repeat(2) {
                h.failReads = true
                backgroundScope.launch(producer) { h.local.themeModeFlow.collect {} }
                runCurrent()
                assertTrue(h.local.recoveryRequiredFlow.value)
                h.failReads = false
                withContext(producer) { h.local.themeModeFlow.first() }
                assertFalse(h.local.recoveryRequiredFlow.value)
            }
            assertEquals(2L, h.local.storageRecoveryState.value.episode)
            assertTrue(h.local.storageRecoveryState.value.generation >= 2)
            release.complete(Unit)
            assertTrue(start.await())
            assertFalse("a queued activation cannot replay after unobserved failure/recovery cycles", activation.await())
            runCurrent()
            assertEquals("synthetic", h.repository.readEnforcementSnapshot().activeProfile!!.id)
        } finally { h.close() }
    }

    @Test fun malformedRecoveryMustNotBecomeASuccessfulNull() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("malformed.preferences_pb")
        }
        val raw = """{"profileId":"synthetic","durationMs":999999999999999999999999}"""
        val key = stringPreferencesKey("emergency_recovery_json")
        store.edit { it[key] = raw }
        val local = LocalDataStore(store)
        val read = async { local.emergencyRecoveryFlow.first() }
        runCurrent()
        assertFalse("malformed data must be an explicit failure, not absent recovery", read.isCompleted)
        assertTrue(local.recoveryRequiredFlow.value)
        assertEquals(raw, store.data.first()[key])
        read.cancel()
    }
}
