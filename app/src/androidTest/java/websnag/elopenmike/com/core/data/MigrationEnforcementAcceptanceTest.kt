package websnag.elopenmike.com.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.UnlockCondition
import java.security.MessageDigest
import java.util.Base64

/** Runtime acceptance gate, separate from successful disk-rollback and no-unlock-result assertions. */
@RunWith(AndroidJUnit4::class)
class MigrationEnforcementAcceptanceTest {
    private lateinit var harness: MigrationStoreHarness
    private val protector = object : TagIdentityProtector {
        override fun fingerprint(rawUid: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(rawUid.trim().uppercase().toByteArray()))
    }
    @Before fun setup() { harness = MigrationStoreHarness() }
    @After fun cleanup() = runBlocking { if (::harness.isInitialized) harness.close() }

    /** Packages the engine must keep reachable in every state, including a failed initialization. */
    private val alwaysReachable = listOf(
        "com.android.emergency", "com.android.phone", "com.android.telecom",
        "com.google.android.dialer", "com.android.systemui", "websnag.elopenmike.com"
    )

    @Test fun failedMigrationMustNotSilentlyDisableRuntimeBlocking() = runBlocking {
        for (fixture in listOf("dormant", "duration-unbound")) {
            harness.seed(fixture)
            val original = harness.raw()
            harness.open(webSnagPreferenceMigrations(protector))
            val errors = Channel<Throwable>(Channel.UNLIMITED)
            // Any uncaught production observer failure would land here instead of being handled.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
                errors.trySend(failure)
            })
            val engine = EnforcementEngine(DefaultProfileRepository(harness.local), harness.local, scope) { true }
            try {
                withTimeout(10_000) {
                    if (fixture == "dormant") {
                        engine.enforcementState.first { it.isBlockingActive }
                    } else {
                        // The abort surfaces as an explicit recovery posture, not as an uncaught
                        // observer failure and not as a successful empty or inactive state.
                        engine.enforcementState.first { it.storageRecoveryRequired }
                        assertFalse(
                            "a failed migration must never be reported as an active session",
                            engine.enforcementState.value.isBlockingActive
                        )
                        assertNull(
                            "no production observer may fail with an uncaught error",
                            errors.tryReceive().getOrNull()
                        )
                        harness.open()
                        assertTrue("runtime failure must retain original preferences", original == harness.raw())
                    }
                }
                // This is the same package decision used by WebSnagAccessibilityService.
                assertTrue("persisted protected session must still block after $fixture startup",
                    engine.isPackageBlocked("invalid.synthetic.distraction"))
                alwaysReachable.forEach {
                    assertFalse("$it must stay reachable after $fixture startup", engine.isPackageBlocked(it))
                }
            } finally {
                engine.stop()
                scope.coroutineContext[Job]!!.cancelAndJoin()
                errors.close()
            }
        }
    }

    @Test fun approvedRecoveryRestartsTheIntendedSessionWithoutWeakeningIt() = runBlocking {
        harness.seed("duration-unbound")
        var approved = false
        harness.open(webSnagPreferenceMigrations(protector, takeLegacyUnlockApproval = { approved.also { approved = false } }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var engine = EnforcementEngine(DefaultProfileRepository(harness.local), harness.local, scope) { true }
        try {
            withTimeout(10_000) { engine.enforcementState.first { it.storageRecoveryRequired } }
            approved = true
            harness.local.retryReadingPersistedState()
            val state = withTimeout(20_000) { engine.enforcementState.first { it.isBlockingActive } }

            assertEquals("synthetic-profile-active", state.activeProfile!!.id)
            assertEquals(setOf("invalid.synthetic.allowed"), state.blockedPackages)
            assertFalse("recovery must clear the failure posture", state.storageRecoveryRequired)
            assertEquals(
                UnlockCondition.RequireNfcTag(
                    requiredTagId = null,
                    allowAnyEnrolledTag = false,
                    allowEmergencyUnlock = true,
                    emergencyCooldownMinutes = 5,
                    requireIntentionPhrase = true
                ),
                state.activeProfile!!.unlockCondition
            )
            assertTrue(engine.isPackageBlocked("invalid.synthetic.distraction"))
            assertFalse(engine.isPackageBlocked("invalid.synthetic.allowed"))
            alwaysReachable.forEach { assertFalse(engine.isPackageBlocked(it)) }
        } finally {
            engine.stop()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }

        // Restarting on the converted state keeps blocking without a second approval.
        harness.open(webSnagPreferenceMigrations(protector))
        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = EnforcementEngine(DefaultProfileRepository(harness.local), harness.local, restartScope) { true }
        try {
            withTimeout(10_000) { engine.enforcementState.first { it.isBlockingActive } }
            assertTrue(engine.isPackageBlocked("invalid.synthetic.distraction"))
            alwaysReachable.forEach { assertFalse(engine.isPackageBlocked(it)) }
        } finally {
            engine.stop()
            restartScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
