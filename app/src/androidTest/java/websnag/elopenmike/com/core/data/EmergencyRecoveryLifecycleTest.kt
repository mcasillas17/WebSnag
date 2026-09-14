package websnag.elopenmike.com.core.data

import android.content.Context
import android.os.Process
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.enforcement.EmergencyClock
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.EmergencyRecovery
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * These methods deliberately require separate instrumentation invocations, in this order:
 * seedRecovery -> host force-stop -> verifyProcessRestoration -> host reboot ->
 * verifyRebootRestoration. device_tests.py excludes this class from its ordinary invocation
 * and makes all three ordered results mandatory in both smoke and full.
 *
 * Only the fixture/phase metadata is test code. Timing, policy, persistence and restoration use
 * the production classes and the real Android clock in the target application process.
 */
@RunWith(AndroidJUnit4::class)
class EmergencyRecoveryLifecycleTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.filesDir, "synthetic-enf-lifecycle")
    private val marker = File(directory, "phase.json")
    private val json = Json { encodeDefaults = true }
    private val clock = EmergencyClock.android(context)
    private var storeScope: CoroutineScope? = null
    private var engineScope: CoroutineScope? = null
    private var engine: EnforcementEngine? = null
    private lateinit var local: LocalDataStore
    private lateinit var repository: DefaultProfileRepository

    @Serializable
    private data class PhaseMarker(
        val stage: String,
        val processId: Int,
        val processNonce: String,
        val lastElapsedMs: Long,
        val profile: Profile,
        val recovery: EmergencyRecovery
    )

    private fun openStore() {
        check(directory.isDirectory) { "Lifecycle fixture must be seeded by the ordered host gate." }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storeScope = scope
        local = LocalDataStore(PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "recovery.preferences_pb")
        })
        repository = DefaultProfileRepository(local)
    }

    private suspend fun startEngine(): EnforcementEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engineScope = scope
        val result = EnforcementEngine(repository, local, scope, clock)
        engine = result
        withTimeout(10_000) { result.enforcementState.first { it.isBlockingActive } }
        return result
    }

    private fun readMarker(stage: String): PhaseMarker {
        check(marker.isFile && marker.length() in 1..16_384) { "Missing or invalid lifecycle phase marker." }
        return json.decodeFromString<PhaseMarker>(marker.readText()).also {
            check(it.stage == stage) { "Lifecycle phases must execute exactly once in order." }
        }
    }

    private fun persistMarker(value: PhaseMarker) {
        // The host reboots only after the test reports success; sync the test-side witness first.
        FileOutputStream(marker).use {
            it.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }

    @After fun close() = runBlocking {
        engine?.stop()
        engineScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        storeScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        engine = null
        engineScope = null
        storeScope = null
    }

    @Test fun seedRecovery() = runBlocking {
        check(!directory.exists() && directory.mkdir()) {
            "Stale lifecycle fixture; run the fresh-install host gate instead of reusing phase results."
        }
        openStore()
        repository.saveProfile(Profile(
            id = "synthetic-lifecycle", name = "Synthetic lifecycle",
            blockedPackages = setOf("invalid.synthetic.distraction"),
            unlockCondition = UnlockCondition.RequireNfcTag(
                emergencyCooldownMinutes = 5, requireIntentionPhrase = false
            )
        ))
        // Fixture setup uses the real repository, not a production test-only activation hook.
        repository.setActiveProfile("synthetic-lifecycle")
        val engine = startEngine()
        assertTrue(engine.startEmergencyUnlock(false))
        val state = withTimeout(10_000) {
            engine.enforcementState.first { it.emergencyCooldownActive && it.remainingEmergencyMs < 299_000L }
        }
        val stored = repository.readEnforcementSnapshot()
        val recovery = stored.recovery!!
        assertNotNull("the test emulator must expose a trusted boot identity", recovery.bootId)
        assertEquals(clock.bootId(), recovery.bootId)
        assertEquals(300_000L, recovery.durationMs)
        assertFalse(recovery.intentionConfirmed)
        assertEquals(stored.activeProfile!!.sessionId, recovery.sessionId)
        assertTrue(state.remainingEmergencyMs > 0)
        persistMarker(PhaseMarker(
            "seeded", Process.myPid(), PROCESS_NONCE, clock.elapsedRealtime(),
            stored.activeProfile, recovery
        ))
    }

    @Test fun verifyProcessRestoration() = runBlocking {
        val before = readMarker("seeded")
        assertNotEquals("force-stop must replace the application process", before.processId, Process.myPid())
        assertNotEquals(before.processNonce, PROCESS_NONCE)
        assertEquals("process restoration must stay on the same boot", before.recovery.bootId, clock.bootId())
        assertTrue(clock.elapsedRealtime() > before.lastElapsedMs)
        openStore()
        assertEquals(EnforcementSnapshot(before.profile, before.recovery), repository.readEnforcementSnapshot())
        val engine = startEngine()
        val state = withTimeout(10_000) { engine.enforcementState.first { it.emergencyCooldownActive } }
        assertEquals(before.recovery, local.emergencyRecoveryFlow.first())
        assertEquals(before.profile, state.activeProfile)
        val elapsedAtAssertion = clock.elapsedRealtime()
        val start = before.recovery.startedAtElapsedMs!!
        assertTrue("same-boot downtime must retain progress, not restart", state.remainingEmergencyMs < before.recovery.durationMs)
        assertTrue("the seeded cooldown must not have expired before verification", state.remainingEmergencyMs > 0)
        assertTrue(state.remainingEmergencyMs >= before.recovery.durationMs - (elapsedAtAssertion - start))
        assertTrue(state.remainingEmergencyMs <= before.recovery.durationMs - (before.lastElapsedMs - start))
        assertFalse(engine.requestEnd(before.profile.id, EndRequest.Emergency(true, true)))
        persistMarker(before.copy(
            stage = "process-verified", processId = Process.myPid(),
            processNonce = PROCESS_NONCE, lastElapsedMs = elapsedAtAssertion
        ))
    }

    @Test fun verifyRebootRestoration() = runBlocking {
        val before = readMarker("process-verified")
        val currentBoot = clock.bootId()
        assertNotNull("reboot evidence requires a readable native boot identity", currentBoot)
        assertNotEquals("the host must actually reboot, not only restart instrumentation", before.recovery.bootId, currentBoot)
        assertNotEquals(before.processNonce, PROCESS_NONCE)
        openStore()
        assertEquals(EnforcementSnapshot(before.profile, before.recovery), repository.readEnforcementSnapshot())
        val beforeRestoreElapsed = clock.elapsedRealtime()
        val engine = startEngine()
        val state = withTimeout(10_000) {
            engine.enforcementState.first {
                it.emergencyCooldownActive && it.emergencyRecovery?.requestId != before.recovery.requestId
            }
        }
        val stored = repository.readEnforcementSnapshot()
        val recovery = stored.recovery!!
        assertEquals(before.profile, stored.activeProfile)
        assertEquals(before.recovery.sessionId, recovery.sessionId)
        assertEquals(currentBoot, recovery.bootId)
        assertNotEquals(before.recovery.requestId, recovery.requestId)
        assertEquals(300_000L, recovery.durationMs)
        assertTrue("new anchor must be persisted after this reboot's restoration begins",
            recovery.startedAtElapsedMs!! >= beforeRestoreElapsed)
        assertTrue(state.remainingEmergencyMs > 0)
        assertTrue("reboot downtime must consume none of the new full wait",
            state.remainingEmergencyMs >= 300_000L - (clock.elapsedRealtime() - beforeRestoreElapsed))
        assertTrue(engine.isPackageBlocked("invalid.synthetic.distraction"))
        assertFalse(engine.isPackageBlocked("com.android.phone"))
        assertFalse(engine.requestEnd(before.profile.id, EndRequest.Emergency(true, true)))
        close()
        check(directory.deleteRecursively()) { "Could not remove the owned lifecycle fixture." }
    }

    private companion object {
        // New JVM process/class loader witness, not a new EnforcementEngine instance.
        val PROCESS_NONCE: String = UUID.randomUUID().toString()
    }
}
