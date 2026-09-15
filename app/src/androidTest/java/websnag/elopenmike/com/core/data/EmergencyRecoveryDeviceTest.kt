package websnag.elopenmike.com.core.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import websnag.elopenmike.com.core.enforcement.EmergencyClock
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import java.util.concurrent.atomic.AtomicLong

/** Real PreferencesDataStore close/reopen and engine commits, without changing device wall time. */
class EmergencyRecoveryDeviceTest {
    private lateinit var harness: MigrationStoreHarness
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: EnforcementEngine
    private val elapsed = AtomicLong(10_000)
    @Volatile private var boot: String? = "synthetic-boot-a"
    private val clock = EmergencyClock({ elapsed.get() }, { boot })

    @Before fun setup() = runBlocking {
        harness = MigrationStoreHarness()
        harness.open()
        val repository = DefaultProfileRepository(harness.local)
        repository.saveProfile(Profile("synthetic-emergency", "Synthetic emergency",
            blockedPackages = emptySet(), unlockCondition = UnlockCondition.RequireNfcTag(
                emergencyCooldownMinutes = 1, requireIntentionPhrase = false)))
        repository.setActiveProfile("synthetic-emergency")
        openEngine()
    }

    private suspend fun openEngine() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = EnforcementEngine(DefaultProfileRepository(harness.local), harness.local, scope, clock)
        withTimeout(10_000) { engine.enforcementState.first { it.isBlockingActive } }
    }

    private suspend fun closeEngine() {
        engine.stop()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @After fun cleanup() = runBlocking {
        if (::engine.isInitialized) closeEngine()
        if (::harness.isInitialized) harness.close()
    }

    @Test fun durableCompletionAtExactBoundarySurvivesStoreReopen() = runBlocking {
        assertTrue(engine.startEmergencyUnlock(false))
        elapsed.addAndGet(59_999)
        withTimeout(10_000) { engine.enforcementState.first { it.remainingEmergencyMs == 1L } }
        assertNotNull(DefaultProfileRepository(harness.local).readEnforcementSnapshot().activeProfile)
        assertFalse(engine.requestEnd("synthetic-emergency", EndRequest.Emergency(true, true)))
        elapsed.incrementAndGet()
        withTimeout(10_000) { engine.enforcementState.first { !it.isBlockingActive } }
        assertNull(harness.local.emergencyRecoveryFlow.first())
        closeEngine()
        harness.open()
        val snapshot = DefaultProfileRepository(harness.local).readEnforcementSnapshot()
        assertNull(snapshot.activeProfile)
        assertNull(snapshot.recovery)
    }

    @Test fun processStyleCloseReopenRetainsElapsedProgressAndRequestIdentity() = runBlocking {
        assertTrue(engine.startEmergencyUnlock(false))
        val before = harness.local.emergencyRecoveryFlow.first()!!
        closeEngine()
        elapsed.addAndGet(20_000)
        harness.open()
        openEngine()
        val state = withTimeout(10_000) { engine.enforcementState.first { it.emergencyCooldownActive } }
        assertEquals(40_000L, state.remainingEmergencyMs)
        assertEquals(before, harness.local.emergencyRecoveryFlow.first())
        assertTrue(engine.cancelEmergencyUnlock(before.requestId!!))
        assertNull(harness.local.emergencyRecoveryFlow.first())
        closeEngine()
        harness.open()
        openEngine()
        assertNull(harness.local.emergencyRecoveryFlow.first())
        assertTrue(engine.enforcementState.value.isBlockingActive)
        assertFalse(engine.enforcementState.value.emergencyCooldownActive)
    }

    @Test fun rebootAndUnavailableBootIdentityRestartFullPersistedWait() = runBlocking {
        for (nextBoot in listOf("synthetic-boot-b", null)) {
            assertTrue(engine.startEmergencyUnlock(false))
            val before = harness.local.emergencyRecoveryFlow.first()!!
            closeEngine()
            boot = nextBoot
            elapsed.addAndGet(500_000)
            harness.open()
            openEngine()
            val state = withTimeout(10_000) {
                engine.enforcementState.first { it.emergencyCooldownActive && it.emergencyRecovery?.requestId != before.requestId }
            }
            assertEquals(60_000L, state.remainingEmergencyMs)
            val after = harness.local.emergencyRecoveryFlow.first()!!
            assertEquals(elapsed.get(), after.startedAtElapsedMs)
            assertNotEquals(before.requestId, after.requestId)
            assertTrue(engine.cancelEmergencyUnlock(after.requestId!!))
        }
    }

    @Test fun releasedFourFieldFixtureRemainsReadableAndCannotInventPhraseConfirmation() = runBlocking {
        closeEngine()
        harness.seed("alpha2-current")
        val legacy = harness.local.emergencyRecoveryFlow.first()!!
        assertEquals(1_020_000L, legacy.durationMs)
        assertFalse(legacy.intentionConfirmed)
        assertNull(legacy.requestId)
        openEngine()
        withTimeout(10_000) { engine.enforcementState.first { it.emergencyRecoveryError != null } }
        assertTrue(engine.enforcementState.value.isBlockingActive)
        assertFalse(engine.enforcementState.value.emergencyCooldownActive)
        assertFalse(engine.startEmergencyUnlock(false))
        val identified = harness.local.emergencyRecoveryFlow.first()!!
        assertEquals(legacy, identified.copy(requestId = null))
        assertTrue(engine.cancelEmergencyUnlock(identified.requestId!!))
        assertTrue(engine.startEmergencyUnlock(true))
        assertEquals(1_020_000L, engine.enforcementState.value.remainingEmergencyMs)
    }
}
