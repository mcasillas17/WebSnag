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

    @Test fun failedMigrationMustNotSilentlyDisableRuntimeBlocking() = runBlocking {
        // This remains an unmet acceptance gate until production can recover failed initialization.
        for (fixture in listOf("dormant", "duration-unbound")) {
            harness.seed(fixture)
            val original = harness.raw()
            harness.open(webSnagPreferenceMigrations(protector))
            val errors = Channel<Throwable>(Channel.UNLIMITED)
            // Contain the production observers' uncaught failures inside the test process.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
                errors.trySend(failure)
            })
            val engine = EnforcementEngine(DefaultProfileRepository(harness.local), harness.local, scope) { true }
            try {
                withTimeout(10_000) {
                    if (fixture == "dormant") {
                        engine.enforcementState.first { it.isBlockingActive }
                    } else {
                        // The active-profile and recovery observers both fail during initialization.
                        repeat(2) { assertTrue(errors.receive() is LegacyTagMigrationException) }
                        harness.open()
                        assertTrue("runtime failure must retain original preferences", original == harness.raw())
                    }
                }
                // This is the same package decision used by WebSnagAccessibilityService.
                assertTrue("persisted protected session must still block after $fixture startup",
                    engine.isPackageBlocked("invalid.synthetic.distraction"))
            } finally {
                engine.stop()
                scope.coroutineContext[Job]!!.cancelAndJoin()
                errors.close()
            }
        }
    }
}
