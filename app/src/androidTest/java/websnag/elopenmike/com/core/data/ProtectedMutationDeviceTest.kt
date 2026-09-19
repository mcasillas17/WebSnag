package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import websnag.elopenmike.com.core.backup.BackupRepository
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.core.nfc.NfcActionResolver
import java.security.KeyStore
import java.util.UUID

class ProtectedMutationDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private lateinit var harness: MigrationStoreHarness
    private lateinit var profiles: DefaultProfileRepository
    private lateinit var tags: DefaultNfcTagRepository
    private lateinit var engine: EnforcementEngine
    private lateinit var tag: NfcTagRecord
    private lateinit var active: Profile
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alias = "synthetic.websnag.mutations.${UUID.randomUUID()}"

    @Before fun setup() = runBlocking {
        harness = MigrationStoreHarness()
        harness.open()
        profiles = DefaultProfileRepository(harness.local)
        tags = DefaultNfcTagRepository(harness.local, AndroidKeystoreTagIdentityProtector(alias))
        tag = checkNotNull(tags.enrollTag("04D10203040506", "Synthetic tag", null, ""))
        profiles.saveProfile(Profile("synthetic-protected", "Synthetic protected",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = tag.id)))
        engine = EnforcementEngine(profiles, harness.local, scope, hasEnrolledNfcTag = { tags.getTags().isNotEmpty() })
        assertTrue(engine.tryActivateProfile("synthetic-protected"))
        active = profiles.readEnforcementSnapshot().activeProfile!!
    }

    @After fun cleanup() = runBlocking {
        try {
            if (::engine.isInitialized) engine.stop()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            if (::harness.isInitialized) harness.close()
        } finally { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    private suspend fun refusedWithoutWrites(operation: suspend () -> Unit) {
        val before = harness.raw()
        assertTrue("protected mutation must explicitly refuse",
            runCatching { operation() }.exceptionOrNull() is IllegalStateException)
        assertTrue("a refused mutation must preserve every preference", before == harness.raw())
        assertEquals(active, profiles.readEnforcementSnapshot().activeProfile)
    }

    @Test fun activeProfileEditAndDeleteRefuseWithoutPartialWrites() = runBlocking {
        refusedWithoutWrites { profiles.saveProfile(active.copy(unlockCondition = UnlockCondition.ManualOnly)) }
        refusedWithoutWrites { profiles.deleteProfile(active.id) }
    }

    @Test fun requiredTagDeletionRefusesWithoutPartialWrites() = runBlocking {
        refusedWithoutWrites { tags.deleteTag(tag.id) }
    }

    @Test fun requiredTagIdentityReplacementRefusesWithoutPartialWrites() = runBlocking {
        refusedWithoutWrites { tags.enrollTag("04E10203040506", "Synthetic replacement", null, "", tag.id) }
    }

    @Test fun activeAnyPolicyCannotBeBroadenedByEnrollingAnotherCredential() = runBlocking {
        assertTrue(engine.requestEnd(active.id, EndRequest.ScheduleEnded))
        profiles.saveProfile(active.copy(isActive = false,
            unlockCondition = UnlockCondition.RequireNfcTag(allowAnyEnrolledTag = true)))
        assertTrue(engine.tryActivateProfile(active.id))
        active = profiles.readEnforcementSnapshot().activeProfile!!
        refusedWithoutWrites { tags.enrollTag("04E10203040506", "Synthetic new", null, "") }
    }

    @Test fun deleteAllRefusesActiveSessionAndSucceedsOnlyAfterAuthorizedEnd() = runBlocking {
        refusedWithoutWrites { harness.local.deleteAllUserData() }
        assertTrue(engine.requestEnd(active.id, EndRequest.ScheduleEnded))
        engine.stop()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        harness.local.deleteAllUserData()
        harness.open()
        assertTrue(harness.local.profilesFlow.first().isEmpty())
        assertTrue(harness.local.nfcTagsFlow.first().isEmpty())
        assertNull(harness.local.emergencyRecoveryFlow.first())
    }

    @Test fun encryptedRestoreRefusesActiveSessionAndPreservesPolicyOnReopen() = runBlocking {
        val repository = BackupRepository(harness.local, profiles)
        val passphrase = "synthetic test passphrase only".toCharArray()
        try {
            val envelope = repository.export(passphrase, includeHistory = true)
            val before = harness.raw()
            assertEquals(BackupRepository.RestoreResult.ActiveLockConflict, repository.restore(envelope, passphrase))
            assertTrue(before == harness.raw())
            engine.stop()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            harness.open()
            assertTrue(before == harness.raw())
            assertEquals(active, DefaultProfileRepository(harness.local).readEnforcementSnapshot().activeProfile)
        } finally { passphrase.fill('\u0000') }
    }

    @Test fun eitherActiveMarkerProtectsCredentialsAndDeleteAll() = runBlocking {
        engine.stop()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val original = harness.raw()
        for (idOnly in listOf(true, false)) {
            harness.store.updateData {
                original.toMutablePreferences().apply {
                    if (idOnly) this[stringPreferencesKey("profiles_json")] =
                        Json.encodeToString(listOf(active.copy(isActive = false)))
                    else remove(stringPreferencesKey("active_profile_id"))
                }
            }
            val before = harness.raw()
            assertTrue(runCatching { tags.deleteTag(tag.id) }.exceptionOrNull() is ActiveSessionMutationException)
            assertTrue(runCatching { harness.local.deleteAllUserData() }.exceptionOrNull() is ActiveSessionMutationException)
            assertTrue("either marker must independently preserve all state", before == harness.raw())
        }
    }

    @Test fun activeSessionStillAllowsTagMetadataAndUsageUpdates() = runBlocking {
        tags.saveTag(tag.copy(label = "Synthetic renamed", description = "Synthetic note"))
        tags.recordTagUsage(tag.id)
        val after = tags.getTags().single()
        assertEquals(tag.id, after.id)
        assertEquals(tag.uidFingerprint, after.uidFingerprint)
        assertEquals("Synthetic renamed", after.label)
        assertNotNull(after.lastUsedEpochMs)
        assertEquals(active, profiles.readEnforcementSnapshot().activeProfile)
    }

    @Test fun usageWriteCannotResurrectDeletedTagsOrCrashWhenActivationInterleaves() = runBlocking {
        assertTrue(engine.requestEnd(active.id, EndRequest.ScheduleEnded))
        val other = checkNotNull(tags.enrollTag("04E10203040506", "Synthetic other", null, ""))
        var intercept = true
        val interleaving = object : DataStore<Preferences> by harness.store {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (intercept) {
                    intercept = false
                    tags.deleteTag(other.id)
                    assertTrue(engine.tryActivateProfile(active.id))
                }
                return harness.store.updateData(transform)
            }
        }
        val racingTags = DefaultNfcTagRepository(LocalDataStore(interleaving), AndroidKeystoreTagIdentityProtector(alias))
        val result = runCatching { NfcActionResolver(profiles, racingTags).resolve("04D10203040506") }
        assertTrue("a usage update must not replay old identities or throw a mutation refusal", result.isSuccess)
        assertTrue(tags.getTags().map { it.id } == listOf(tag.id))
        assertNotNull(tags.getTags().single().lastUsedEpochMs)
        assertEquals(active.id, profiles.readEnforcementSnapshot().activeProfile?.id)
    }
}
