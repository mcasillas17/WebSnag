package websnag.elopenmike.com.core.schedule

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.WebSnagApp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the schedule receivers through their real `onReceive` and observes the app's own
 * persisted state. Every reconcile pass rewrites the reconciliation record, so an unchanged store
 * proves a rejected delivery reconciled nothing and persisted nothing.
 *
 * Deliveries use real explicit ordered broadcasts wherever an app may send the action: the result
 * callback runs only once the receiver has finished, including its `goAsync()` work. The declared
 * system actions are protected broadcasts that only the system can send, so those are handed to
 * `onReceive` directly, where the framework supplies no pending result.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleReceiverActionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = context as WebSnagApp
    private val store = File(context.filesDir, "datastore/websnag_preferences.preferences_pb")
    private val alarmReceiver = ComponentName(context, ScheduleAlarmReceiver::class.java)
    private val systemReceiver = ComponentName(context, SystemScheduleReceiver::class.java)

    @Before
    fun waitForStartupWritesToSettle() = runBlocking {
        withTimeout(20_000) { app.localDataStore.scheduleReconciliationFlow.first { it != null } }
        awaitQuietStore()
    }

    @Test
    fun alarmReceiverReconcilesItsInternalActionOnce() = runBlocking {
        val passes = passesDuring { since ->
            deliver(Intent(ACTION_RECONCILE).setComponent(alarmReceiver))
            assertTrue("the internal alarm action must reconcile before finishing", reconciledAt() > since)
        }

        assertEquals(1, passes)
    }

    @Test
    fun alarmReceiverIgnoresMissingUnknownAndSystemActions() = runBlocking {
        val before = storeBytes()

        deliver(Intent().setComponent(alarmReceiver))
        deliver(Intent(UNKNOWN_ACTION).setComponent(alarmReceiver))
        SYSTEM_ACTIONS.forEach { ScheduleAlarmReceiver().onReceive(context, Intent(it)) }

        assertStoreUnchanged(before)
    }

    @Test
    fun systemReceiverIgnoresMissingUnknownAndInternalActions() = runBlocking {
        val before = storeBytes()

        deliver(Intent().setComponent(systemReceiver))
        deliver(Intent(UNKNOWN_ACTION).setComponent(systemReceiver))
        deliver(Intent(ACTION_RECONCILE).setComponent(systemReceiver))

        assertStoreUnchanged(before)
    }

    @Test
    fun systemReceiverReconcilesEachDeclaredSystemActionOnce() = runBlocking {
        for (action in SYSTEM_ACTIONS) {
            val passes = passesDuring { SystemScheduleReceiver().onReceive(context, Intent(action)) }

            assertEquals(action, 1, passes)
        }
    }

    @Test
    fun manifestRoutesExactlyTheDeclaredSystemActionsToTheSystemReceiver() {
        for (action in SYSTEM_ACTIONS + ACTION_RECONCILE) {
            @Suppress("DEPRECATION")
            val resolved = context.packageManager.queryBroadcastReceivers(
                Intent(action).setPackage(context.packageName),
                PackageManager.GET_RESOLVED_FILTER
            )
            val routed = resolved.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            val expected = if (action in SYSTEM_ACTIONS) listOf(systemReceiver) else emptyList()
            assertEquals(action, expected, routed)
            // A widened exported filter would fail here even though the receiver would reject it.
            resolved.forEach {
                assertEquals(action, SYSTEM_ACTIONS.toSet(), it.filter.actionsIterator().asSequence().toSet())
            }
        }
    }

    /** Sends [intent] as a real ordered broadcast and returns once its receiver has finished. */
    private fun deliver(intent: Intent) {
        val finished = CountDownLatch(1)
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = finished.countDown()
            },
            null,
            Activity.RESULT_OK,
            null,
            null
        )
        assertTrue("the receiver must finish the broadcast", finished.await(20, TimeUnit.SECONDS))
    }

    /**
     * Counts the reconciliation passes [deliver] causes: distinct records written after it starts,
     * watched until the store has stayed quiet for [QUIET_MS], so a later second pass is caught.
     * ponytail: two passes landing within one store emission count once; a per-pass hook would be exact.
     */
    private suspend fun passesDuring(deliver: suspend (since: Long) -> Unit): Int = coroutineScope {
        val since = reconciledAt()
        val passes = ConcurrentHashMap.newKeySet<Long>()
        val watching = CompletableDeferred<Unit>()
        val watcher = launch(Dispatchers.Default) {
            app.localDataStore.scheduleReconciliationFlow.collect { record ->
                record?.timestampEpochMs?.takeIf { it > since }?.let(passes::add)
                watching.complete(Unit)
            }
        }
        watching.await()
        deliver(since)
        withTimeout(10_000) { while (passes.isEmpty()) delay(50) }
        awaitQuietStore()
        watcher.cancel()
        passes.size
    }

    /** A wrongly accepted direct delivery reconciles asynchronously, so keep watching briefly. */
    private suspend fun assertStoreUnchanged(before: ByteArray) {
        repeat(QUIET_MS.toInt() / 100) {
            assertArrayEquals("a rejected delivery must not change persisted state", before, storeBytes())
            delay(100)
        }
    }

    private suspend fun awaitQuietStore() = withTimeout(20_000) {
        var previous = storeBytes()
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - quietSince < QUIET_MS) {
            delay(100)
            val current = storeBytes()
            if (!current.contentEquals(previous)) {
                previous = current
                quietSince = System.currentTimeMillis()
            }
        }
    }

    private suspend fun reconciledAt(): Long =
        app.localDataStore.scheduleReconciliationFlow.first()!!.timestampEpochMs

    private fun storeBytes(): ByteArray = store.readBytes()

    private companion object {
        const val QUIET_MS = 1_500L
        /** The wire value already held by scheduled alarms; it must not change. */
        const val ACTION_RECONCILE = "websnag.action.RECONCILE_SCHEDULES"
        const val UNKNOWN_ACTION = "websnag.action.UNKNOWN"
        val SYSTEM_ACTIONS = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
