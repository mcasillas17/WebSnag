package websnag.elopenmike.com.core.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import websnag.elopenmike.com.FakeProfileRepository
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.schedule.ScheduleManager
import java.io.File
import java.io.IOException

/** How runtime consumers behave while persisted state is unreadable. */
class EnforcementRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val alwaysFails = object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = true
        override suspend fun migrate(currentData: Preferences): Preferences = throw IOException("unreadable")
        override suspend fun cleanUp() = Unit
    }

    @Test fun approvedRecoveryReactivatesThePersistedSessionForALiveEngine() = runBlocking {
        val file: File = temporary.newFolder().resolve("fixture.preferences_pb")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            PreferenceDataStoreFactory.create(scope = seedScope) { file }
                .updateData { MigrationFixtures.load("duration-unbound") }
        } finally { seedScope.coroutineContext[Job]!!.cancelAndJoin() }

        var approved = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val local = LocalDataStore(
            PreferenceDataStoreFactory.create(
                migrations = webSnagPreferenceMigrations(MigrationFixtures.protector, takeLegacyUnlockApproval = { approved.also { approved = false } }),
                scope = scope
            ) { file }
        )
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = EnforcementEngine(DefaultProfileRepository(local), local, engineScope) { true }
        try {
            withTimeout(10_000) { engine.enforcementState.first { it.storageRecoveryRequired } }
            // A pause is only ever "until data loads": recovery must re-arm enforcement by itself.
            engine.pauseRecoveryLockdown()
            approved = true
            local.retryReadingPersistedState()
            val state = withTimeout(20_000) { engine.enforcementState.first { it.isBlockingActive } }
            assertEquals("synthetic-profile-active", state.activeProfile!!.id)
            assertFalse(state.storageRecoveryRequired)
            assertFalse("a loaded store must re-arm enforcement", state.recoveryLockdownPaused)
            assertTrue(engine.isPackageBlocked("invalid.synthetic.distraction"))
            assertFalse(engine.isPackageBlocked("invalid.synthetic.allowed"))
        } finally {
            engine.stop()
            engineScope.coroutineContext[Job]!!.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun aSucceedingReadMustAlsoReleaseEveryCollectorParkedByAnEarlierFailure() = runBlocking {
        var fail = true
        val failsUntilRepaired = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = true
            override suspend fun migrate(currentData: Preferences): Preferences =
                if (fail) throw IOException("unreadable") else currentData
            override suspend fun cleanUp() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val local = LocalDataStore(
            PreferenceDataStoreFactory.create(migrations = listOf(failsUntilRepaired), scope = scope) {
                temporary.newFolder().resolve("fixture.preferences_pb")
            }
        )
        val parked = CompletableDeferred<Int>()
        val watcher = launch { local.profilesFlow.collect { parked.complete(it.size) } }
        try {
            assertNull(withTimeoutOrNull(3_000) { parked.await() })
            assertTrue(local.recoveryRequiredFlow.value)

            // DataStore re-runs initialization for each new collection, so this independently
            // started read succeeds on its own. It must not clear the recovery state and leave the
            // earlier collector parked, which would disarm enforcement with state never reloaded.
            fail = false
            assertNotNull(withTimeoutOrNull(10_000) { local.themeModeFlow.first() })
            assertNotNull("a parked collector must be released by any observed success",
                withTimeoutOrNull(10_000) { parked.await() })
            assertFalse(local.recoveryRequiredFlow.value)
        } finally {
            watcher.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun theUserCanDeliberatelyLeaveALockdownThatNoRetryCanRepair() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val local = LocalDataStore(
            PreferenceDataStoreFactory.create(migrations = listOf(alwaysFails), scope = scope) {
                temporary.newFolder().resolve("fixture.preferences_pb")
            }
        )
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = EnforcementEngine(DefaultProfileRepository(local), local, engineScope) { true }
        try {
            withTimeout(10_000) { engine.enforcementState.first { it.storageRecoveryRequired } }
            assertTrue(engine.isPackageBlocked("com.android.settings"))

            engine.pauseRecoveryLockdown()

            assertFalse("a deliberate pause must reopen the device", engine.isPackageBlocked("com.android.settings"))
            assertFalse(engine.isPackageBlocked("com.instagram.android"))
            assertTrue("the failure itself must stay visible", engine.enforcementState.value.storageRecoveryRequired)
            assertTrue(engine.enforcementState.value.recoveryLockdownPaused)
        } finally {
            engine.stop()
            engineScope.coroutineContext[Job]!!.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun pausingTheLockdownMustNotReleaseAnAlreadyLoadedSessionLock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val local = LocalDataStore(
            PreferenceDataStoreFactory.create(migrations = listOf(alwaysFails), scope = scope) {
                temporary.newFolder().resolve("fixture.preferences_pb")
            }
        )
        // A session that was already loaded before the store became unreadable.
        val profiles = FakeProfileRepository()
        profiles.saveProfile(
            Profile(
                id = "prof-active", name = "Focus", filterMode = FilterMode.BLOCKLIST,
                blockedPackages = setOf("com.instagram.android"),
                unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = "tag-1"),
                isActive = true
            )
        )
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = EnforcementEngine(profiles, local, engineScope) { true }
        try {
            withTimeout(10_000) { engine.enforcementState.first { it.isBlockingActive } }
            withTimeout(10_000) { engine.enforcementState.first { it.storageRecoveryRequired } }
            assertTrue(engine.isPackageBlocked("com.instagram.android"))
            assertTrue("the lockdown widens blocking while it is in force", engine.isPackageBlocked("com.android.settings"))

            engine.pauseRecoveryLockdown()

            // The pause releases only the extra fail-closed blocking. Ending the loaded session
            // still requires that profile's own unlock policy, not a typed sentence.
            assertTrue("a paused lockdown must not unlock a loaded session",
                engine.isPackageBlocked("com.instagram.android"))
            assertFalse(engine.isPackageBlocked("com.android.settings"))
            assertFalse(engine.enforcementState.value.recoveryLockdownInForce)
        } finally {
            engine.stop()
            engineScope.coroutineContext[Job]!!.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun aScheduleReconcileMustAlwaysCompleteSoABroadcastCanFinish() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val local = LocalDataStore(
            PreferenceDataStoreFactory.create(migrations = listOf(alwaysFails), scope = scope) {
                temporary.newFolder().resolve("fixture.preferences_pb")
            }
        )
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profiles = FakeProfileRepository()
        val engine = EnforcementEngine(profiles, null, managerScope) { true }
        val manager = ScheduleManager(local, profiles, engine, managerScope)
        val completed = CompletableDeferred<Unit>()
        try {
            // ScheduleAlarmReceiver finishes its goAsync() PendingResult from this callback, so it
            // has to run even when every persisted read is waiting for recovery.
            manager.reconcileNow { completed.complete(Unit) }
            assertNotNull("a reconcile must complete while storage is unreadable",
                withTimeoutOrNull(30_000) { completed.await() })
        } finally {
            engine.stop()
            managerScope.coroutineContext[Job]!!.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun writesStillFailWhileStorageIsUnreadableSoErrorReportingMustNotAssumeSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val local = LocalDataStore(
            PreferenceDataStoreFactory.create(migrations = listOf(alwaysFails), scope = scope) {
                temporary.newFolder().resolve("fixture.preferences_pb")
            }
        )
        try {
            assertNull(withTimeoutOrNull(3_000) { local.profilesFlow.first() })
            assertTrue(local.recoveryRequiredFlow.value)
            // Reads wait, but writes still fail: DataStore re-runs the failed initialization. Any
            // caller that reports a problem by persisting one -- MainActivity.recordLocalError is
            // reached from sixteen catch blocks -- would otherwise take the process down with it.
            assertNotNull(
                "a write while storage is unreadable must fail rather than appear to succeed",
                runCatching { local.saveLocalError(1L, ErrorCategory.DIAGNOSTICS) }.exceptionOrNull()
            )
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun unreadablePersistedStateBlocksEveryNonExemptPackage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val local = LocalDataStore(
            PreferenceDataStoreFactory.create(migrations = listOf(alwaysFails), scope = scope) {
                temporary.newFolder().resolve("fixture.preferences_pb")
            }
        )
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = EnforcementEngine(DefaultProfileRepository(local), local, engineScope) { true }
        try {
            withTimeout(10_000) { local.recoveryRequiredFlow.first { it } }
            withTimeout(10_000) { engine.enforcementState.first { it.storageRecoveryRequired } }

            assertTrue("unreadable state must not widen authorization", engine.isPackageBlocked("com.instagram.android"))
            assertFalse("emergency dialing must stay reachable", engine.isPackageBlocked("com.android.emergency"))
            assertFalse(engine.isPackageBlocked("com.android.phone"))
            assertFalse(engine.isPackageBlocked("com.android.telecom"))
            assertFalse(engine.isPackageBlocked("com.google.android.dialer"))
            assertFalse("recovery must stay reachable", engine.isPackageBlocked("websnag.elopenmike.com"))
            assertFalse(engine.isPackageBlocked("com.android.systemui"))
            engine.registerExemptPackage("com.android.launcher3")
            assertFalse("the home launcher must stay reachable", engine.isPackageBlocked("com.android.launcher3"))
            assertFalse(engine.isPackageBlocked(""))
            assertFalse(
                "an unreadable store must never be reported as an active session",
                engine.enforcementState.value.isBlockingActive
            )
        } finally {
            engine.stop()
            engineScope.coroutineContext[Job]!!.cancelAndJoin()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
