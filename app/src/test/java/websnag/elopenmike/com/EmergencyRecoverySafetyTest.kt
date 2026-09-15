package websnag.elopenmike.com

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.data.EnforcementSnapshot
import websnag.elopenmike.com.core.enforcement.EmergencyClock
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.enforcement.UnlockPolicy
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyRecoverySafetyTest {
    @Test fun blockedAttemptCannotOverwriteConcurrentRecoveryPause() = runTest {
        val repository = FakeProfileRepository()
        repository.saveProfile(Profile("synthetic", "Synthetic", unlockCondition = UnlockCondition.ManualOnly))
        val engine = EnforcementEngine(repository, coroutineScope = backgroundScope, hasEnrolledNfcTag = { true })
        val workers = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(3)
        val repetitions = 10_000
        try {
            val pause = workers.submit {
                repeat(repetitions) { barrier.await(10, TimeUnit.SECONDS); engine.pauseRecoveryLockdown(); barrier.await(10, TimeUnit.SECONDS) }
            }
            val record = workers.submit {
                repeat(repetitions) { barrier.await(10, TimeUnit.SECONDS); engine.recordBlockedAttempt("invalid.synthetic"); barrier.await(10, TimeUnit.SECONDS) }
            }
            var lostPauses = 0
            repeat(repetitions) {
                assertTrue(engine.tryActivateProfile("synthetic"))
                runCurrent()
                barrier.await(10, TimeUnit.SECONDS)
                barrier.await(10, TimeUnit.SECONDS)
                if (!engine.enforcementState.value.recoveryLockdownPaused) lostPauses++
            }
            pause.get()
            record.get()
            assertEquals("blocked-attempt metadata must preserve concurrently changed safety state", 0, lostPauses)
        } finally {
            workers.shutdownNow()
            engine.stop()
        }
    }

    @Test fun optionalPhraseDoesNotNeedInventedConfirmation() {
        assertTrue(UnlockPolicy.canEnd(
            UnlockCondition.RequireNfcTag(requireIntentionPhrase = false),
            EndRequest.Emergency(true, false)
        ))
    }

    @Test fun callerBooleansCannotAuthorizeEmergencyCompletion() = runTest {
        val repository = FakeProfileRepository()
        repository.saveProfile(Profile("strict", "Synthetic", isActive = true))
        val engine = EnforcementEngine(repository, coroutineScope = backgroundScope)
        runCurrent()
        assertFalse(engine.requestEnd("strict", EndRequest.Emergency(true, true)))
        assertTrue(engine.enforcementState.value.isBlockingActive)
    }

    @Test fun completionMustSurviveASuspendingRepositoryWrite() = runTest {
        val backing = FakeProfileRepository()
        val repository = object : ProfileRepository by backing {
            override suspend fun compareAndSetEnforcement(expected: EnforcementSnapshot, updated: EnforcementSnapshot): Boolean {
                delay(1)
                return backing.compareAndSetEnforcement(expected, updated)
            }
        }
        backing.saveProfile(Profile("strict", "Synthetic", isActive = true))
        val engine = EnforcementEngine(repository, coroutineScope = backgroundScope,
            emergencyClock = EmergencyClock({ testScheduler.currentTime }, { "boot-a" }))
        runCurrent()
        assertTrue(engine.startEmergencyUnlock(true))
        runCurrent()
        advanceTimeBy(300_100)
        runCurrent()
        assertFalse("completion must commit, not cancel its own write", engine.enforcementState.value.isBlockingActive)
    }

    @Test fun configuredDurationMustNotOverflowIntArithmetic() = runTest {
        val repository = FakeProfileRepository()
        repository.saveProfile(Profile("strict", "Synthetic", isActive = true,
            unlockCondition = UnlockCondition.RequireNfcTag(emergencyCooldownMinutes = Int.MAX_VALUE)))
        val engine = EnforcementEngine(repository, coroutineScope = backgroundScope,
            emergencyClock = EmergencyClock({ testScheduler.currentTime }, { "boot-a" }))
        runCurrent()
        assertTrue(engine.startEmergencyUnlock(true))
        assertEquals(Int.MAX_VALUE.toLong() * 60_000L, engine.enforcementState.value.emergencyCooldownDurationMs)
    }

    @Test fun invalidConfiguredDurationMustBeRejected() = runTest {
        val repository = FakeProfileRepository()
        repository.saveProfile(Profile("strict", "Synthetic", isActive = true,
            unlockCondition = UnlockCondition.RequireNfcTag(emergencyCooldownMinutes = -1)))
        val engine = EnforcementEngine(repository, coroutineScope = backgroundScope)
        runCurrent()
        assertFalse(engine.startEmergencyUnlock(true))
    }
}
