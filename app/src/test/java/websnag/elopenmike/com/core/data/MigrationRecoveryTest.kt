package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import java.io.File

/**
 * Production migration-failure and recovery behavior for the `duration-unbound` fixture: the
 * unconvertible legacy unlock policy behind MIG-001A's runtime acceptance gate.
 */
class MigrationRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private var approved = false
    private var protector: TagIdentityProtector = MigrationFixtures.protector
    private lateinit var file: File
    private var storeScope: CoroutineScope? = null

    @After fun cleanup() { runBlocking { storeScope?.coroutineContext?.get(Job)?.cancelAndJoin() } }

    private suspend fun seed(fixture: String) {
        file = temporary.newFolder().resolve("fixture.preferences_pb")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seedStore = PreferenceDataStoreFactory.create(scope = seedScope) { file }
        try { seedStore.updateData { MigrationFixtures.load(fixture) } }
        finally { seedScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private fun openMigrating(): LocalDataStore {
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storeScope = next
        return LocalDataStore(
            PreferenceDataStoreFactory.create(
                migrations = webSnagPreferenceMigrations(
                    protector = protector,
                    // A real one-shot approval: taken atomically by the pass that uses it.
                    takeLegacyUnlockApproval = { approved.also { approved = false } }
                ),
                scope = next
            ) { file }
        )
    }

    /** Reads the file through an independent non-migrating store, after closing the migrating one. */
    private suspend fun rawPreferences(): Preferences {
        storeScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return PreferenceDataStoreFactory.create(scope = readScope) { file }.data.first()
        } finally { readScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun unconvertibleLegacyUnlockPolicySurfacesRecoveryInsteadOfEmptyState() = runBlocking {
        seed("duration-unbound")
        val original = rawPreferences()
        val local = openMigrating()
        assertNull(
            "an unreadable store must not emit a successful empty profile list",
            withTimeoutOrNull(2_000) { local.profilesFlow.first() }
        )
        assertTrue("migration failure must be an explicit recovery state", local.recoveryRequiredFlow.value)
        assertEquals("failed migration must retain the original persisted input", original, rawPreferences())
    }

    @Test fun retryWithoutApprovalStaysInRecoveryAndKeepsOriginalInput() = runBlocking {
        seed("duration-unbound")
        val original = rawPreferences()
        val local = openMigrating()
        val emitted = CompletableDeferred<List<Profile>>()
        val watcher = launch { local.profilesFlow.collect { emitted.complete(it) } }
        try {
            assertNull(withTimeoutOrNull(2_000) { emitted.await() })
            local.retryReadingPersistedState()
            assertNull(
                "an unapproved retry must not invent an unlock policy",
                withTimeoutOrNull(2_000) { emitted.await() }
            )
            assertTrue(local.recoveryRequiredFlow.value)
        } finally { watcher.cancelAndJoin() }
        assertEquals(original, rawPreferences())
    }

    @Test fun approvedRetryRecoversALiveCollectorWithTheStrictestCurrentPolicy() = runBlocking {
        seed("duration-unbound")
        val local = openMigrating()
        val emitted = CompletableDeferred<List<Profile>>()
        val watcher = launch { local.profilesFlow.collect { emitted.complete(it) } }
        try {
            assertNull(withTimeoutOrNull(2_000) { emitted.await() })
            approved = true
            local.retryReadingPersistedState()
            val recovered = withTimeout(10_000) { emitted.await() }
            val active = recovered.single { it.id == "synthetic-profile-active" }
            // The replacement is irreversible, so it must reach only the unsupported shape: this
            // profile's own condition has to survive the approved pass untouched.
            assertEquals(
                UnlockCondition.ManualOnly,
                recovered.single { it.id == "synthetic-profile-inactive" }.unlockCondition
            )
            assertTrue("recovery must not deactivate the persisted session", active.isActive)
            assertEquals(setOf("invalid.synthetic.allowed"), active.blockedPackages)
            assertEquals("synthetic-tag-a", active.linkedTagId)
            assertEquals(
                UnlockCondition.RequireNfcTag(
                    requiredTagId = null,
                    allowAnyEnrolledTag = false,
                    allowEmergencyUnlock = true,
                    emergencyCooldownMinutes = 5,
                    requireIntentionPhrase = true
                ),
                active.unlockCondition
            )
            assertFalse(local.recoveryRequiredFlow.value)
            assertEquals(2, local.nfcTagsFlow.first().size)
        } finally { watcher.cancelAndJoin() }
    }

    @Test fun approvedRecoveryNeverRetainsRawLegacyIdentifiers() = runBlocking {
        seed("duration-unbound")
        approved = true
        val local = openMigrating()
        assertNotNull(withTimeoutOrNull(10_000) { local.profilesFlow.first() })
        val stored = rawPreferences()
        listOf("profiles_json", "nfc_tags_json").forEach { key ->
            val value = stored[stringPreferencesKey(key)].orEmpty()
            assertFalse("$key must not retain a raw legacy UID", value.contains("A0B1C2D3"))
            assertFalse("$key must not retain a raw legacy UID", value.contains("D4E5F607"))
            assertFalse("$key must not retain a legacy identity key", value.contains("uidHex"))
            assertFalse("$key must not retain a legacy identity key", value.contains("TagUid"))
        }
    }

    @Test fun anApprovalIsSpentByTheRetryItWasGrantedForAndNeverOutlivesIt() = runBlocking {
        seed("duration-unbound")
        val local = openMigrating()
        assertNull(withTimeoutOrNull(2_000) { local.profilesFlow.first() })
        assertFalse("a pass that never reached the approval must not spend one", approved)

        approved = true
        local.retryReadingPersistedState()
        assertNotNull(withTimeoutOrNull(10_000) { local.profilesFlow.first() })
        assertFalse("a committed pass spends the approval", approved)
    }

    @Test fun oneApprovalConvertsEveryProfileCarryingTheUnsupportedShape() = runBlocking {
        seed("duration-unbound")
        // The screen promises that every lock of that kind is replaced, so one approval has to
        // cover them all: spending it per profile would abort the pass on the second one.
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            PreferenceDataStoreFactory.create(scope = seedScope) { file }.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    val key = stringPreferencesKey("profiles_json")
                    val entries = MigrationFixtures.json.parseToJsonElement(this[key]!!).jsonArray.toMutableList()
                    val unbound = entries[0].jsonObject.getValue("unlockCondition")
                    entries[1] = JsonObject(entries[1].jsonObject + ("unlockCondition" to unbound))
                    this[key] = JsonArray(entries).toString()
                }
            }
        } finally { seedScope.coroutineContext[Job]!!.cancelAndJoin() }

        approved = true
        val local = openMigrating()
        val profiles = withTimeoutOrNull(10_000) { local.profilesFlow.first() }
        assertNotNull("one approval must convert both profiles", profiles)
        assertEquals(2, profiles!!.size)
        val strictest = UnlockCondition.RequireNfcTag(
            requiredTagId = null,
            allowAnyEnrolledTag = false,
            allowEmergencyUnlock = true,
            emergencyCooldownMinutes = 5,
            requireIntentionPhrase = true
        )
        profiles.forEach { assertEquals(strictest, it.unlockCondition) }
        assertFalse("the approval is spent once, not once per profile", approved)
    }

    @Test fun anApprovalIsAlsoSpentByAPassThatReadItAndThenAborted() = runBlocking {
        seed("duration-unbound")
        // The unbound duration sits on the first profile, so the approval is read; the second
        // profile then fails to resolve and aborts the whole pass. Leaving the approval set would
        // let a later plain retry apply the irreversible replacement with no confirmation shown.
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            PreferenceDataStoreFactory.create(scope = seedScope) { file }.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    val key = stringPreferencesKey("profiles_json")
                    val entries = MigrationFixtures.json.parseToJsonElement(this[key]!!).jsonArray.toMutableList()
                    entries[1] = JsonObject(
                        entries[1].jsonObject + ("linkedTagUid" to JsonPrimitive("DEADBEEF"))
                    )
                    this[key] = JsonArray(entries).toString()
                }
            }
        } finally { seedScope.coroutineContext[Job]!!.cancelAndJoin() }
        val original = rawPreferences()

        approved = true
        val local = openMigrating()
        assertNull(withTimeoutOrNull(3_000) { local.profilesFlow.first() })
        assertTrue(local.recoveryRequiredFlow.value)
        assertFalse("an approval read by an aborted pass must not survive it", approved)
        assertEquals(original, rawPreferences())
    }

    @Test fun approvalIsScopedToTheUnlockPolicyAndNotToIdentityProtection() = runBlocking {
        seed("duration-unbound")
        approved = true
        protector = object : TagIdentityProtector { override fun fingerprint(rawUid: String): String? = null }
        val original = rawPreferences()
        val local = openMigrating()
        assertNull(
            "approval must not convert identities without a usable protected fingerprint",
            withTimeoutOrNull(2_000) { local.profilesFlow.first() }
        )
        assertTrue(local.recoveryRequiredFlow.value)
        assertEquals(original, rawPreferences())
    }
}
