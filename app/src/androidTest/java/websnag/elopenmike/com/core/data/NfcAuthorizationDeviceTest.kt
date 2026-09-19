package websnag.elopenmike.com.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import websnag.elopenmike.com.core.enforcement.EmergencyClock
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.core.nfc.NfcActionResolver
import websnag.elopenmike.com.core.nfc.NfcTagAction
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator

/** Software scans only: real repositories, Android HMAC and persistence, not NFC radio evidence. */
class NfcAuthorizationDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private lateinit var harness: MigrationStoreHarness
    private lateinit var profiles: DefaultProfileRepository
    private lateinit var tags: DefaultNfcTagRepository
    private lateinit var resolver: NfcActionResolver
    private lateinit var engine: EnforcementEngine
    private var engineScope: CoroutineScope? = null
    private val alias = "synthetic.websnag.nfc.${UUID.randomUUID()}"
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val protector get() = AndroidKeystoreTagIdentityProtector(alias)
    private val elapsed = AtomicLong(10_000)

    @Before fun setup() = runBlocking {
        harness = MigrationStoreHarness()
        harness.open()
        openEngine()
    }

    private suspend fun openEngine(hasEnrolledTag: suspend () -> Boolean = { tags.getTags().isNotEmpty() }) {
        profiles = DefaultProfileRepository(harness.local)
        tags = DefaultNfcTagRepository(harness.local, protector)
        resolver = NfcActionResolver(profiles, tags,
            storageGeneration = { harness.local.storageRecoveryState.value.generation }) {
            harness.local.recoveryRequiredFlow.value
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engineScope = scope
        engine = EnforcementEngine(profiles, harness.local, scope,
            EmergencyClock({ elapsed.get() }, { "synthetic-nfc-boot" }),
            hasEnrolledNfcTag = hasEnrolledTag, nfcTagRepository = tags)
        profiles.readEnforcementSnapshot().activeProfile?.let { expected ->
            withTimeout(10_000) { engine.enforcementState.first { it.activeProfile == expected } }
        }
    }

    private suspend fun closeEngine() {
        if (::engine.isInitialized) engine.stop()
        engineScope?.coroutineContext?.get(Job)?.cancelAndJoin()
    }

    private suspend fun reload() {
        closeEngine()
        harness.open()
        openEngine()
    }

    @After fun cleanup() = runBlocking {
        try {
            closeEngine()
            if (::harness.isInitialized) harness.close()
        } finally {
            keyStore.deleteEntry(alias)
        }
    }

    private suspend fun enroll(uid: String = UID_A): NfcTagRecord =
        checkNotNull(tags.enrollTag(uid, "Synthetic tag", null, "Synthetic fixture"))

    private suspend fun activate(condition: UnlockCondition, linkedId: String? = null): Profile {
        val profile = Profile("synthetic-nfc-profile", "Synthetic NFC",
            blockedPackages = setOf("invalid.synthetic.distraction"), linkedTagId = linkedId,
            unlockCondition = condition)
        profiles.saveProfile(profile)
        assertTrue(engine.tryActivateProfile(profile.id))
        return profiles.readEnforcementSnapshot().activeProfile!!
    }

    private suspend fun endProposal(proposal: NfcTagAction.DeactivateProfile): Boolean =
        engine.requestNfcEnd(proposal.profile, proposal.tagUid)

    private suspend fun assertProtected(expected: Profile) {
        assertEquals(expected, profiles.readEnforcementSnapshot().activeProfile)
        assertTrue(engine.isPackageBlocked("invalid.synthetic.distraction"))
    }

    @Test fun enrollmentHmacAndSpecificPolicySurviveReloadAndOnlyMatchingTagEndsSession() = runBlocking {
        val correct = enroll()
        enroll(UID_B)
        val active = activate(UnlockCondition.RequireNfcTag(correct.id), correct.id)
        val persisted = harness.raw()
        assertTrue(keyStore.containsAlias(alias))
        assertTrue("raw scan inputs must never be persisted",
            listOf(UID_A, UID_B, "uidHex").none { persisted.asMap().values.joinToString().contains(it) })
        assertTrue("a fingerprint must not be the input", correct.uidFingerprint != UID_A)
        reload()
        assertEquals(correct, tags.getTagForUid(UID_A.lowercase()))
        assertEquals(active, profiles.readEnforcementSnapshot().activeProfile)
        assertTrue(resolver.resolve(UID_B) is NfcTagAction.UnlockRejected)
        assertTrue(resolver.resolve(UID_UNKNOWN) is NfcTagAction.UnlockRejected)
        assertFalse(engine.requestNfcEnd(active, UID_B))
        assertFalse(engine.requestNfcEnd(active, UID_UNKNOWN))
        val proposal = resolver.resolve(UID_A) as NfcTagAction.DeactivateProfile
        assertProtected(active)
        assertTrue(endProposal(proposal))
        reload()
        assertNull(profiles.readEnforcementSnapshot().activeProfile)
        assertNull(harness.local.emergencyRecoveryFlow.first())
    }

    @Test fun explicitAnyPolicyAcceptsOnlyCurrentlyEnrolledTags() = runBlocking {
        enroll()
        enroll(UID_B)
        val removed = enroll(UID_DELETED)
        tags.deleteTag(removed.id)
        val active = activate(UnlockCondition.RequireNfcTag(allowAnyEnrolledTag = true))
        for (input in listOf(UID_DELETED, UID_UNKNOWN, "", "not-a-tag", "A", "AA:BB")) {
            assertTrue("non-enrolled input must not propose an unlock",
                resolver.resolve(input) is NfcTagAction.UnlockRejected)
            assertFalse(engine.requestNfcEnd(active, input))
            assertProtected(active)
        }
        assertTrue(endProposal(resolver.resolve(UID_A) as NfcTagAction.DeactivateProfile))
        assertTrue(engine.tryActivateProfile(active.id))
        assertTrue(endProposal(resolver.resolve(UID_B) as NfcTagAction.DeactivateProfile))
    }

    @Test fun missingBindingAndManualOnlyNeverAcceptNfc() = runBlocking {
        enroll()
        for (condition in listOf(UnlockCondition.RequireNfcTag(), UnlockCondition.ManualOnly)) {
            val active = activate(condition)
            assertTrue(resolver.resolve(UID_A) is NfcTagAction.UnlockRejected)
            assertFalse(engine.requestNfcEnd(active, UID_A))
            assertProtected(active)
            assertEquals(condition == UnlockCondition.ManualOnly, engine.requestEnd(active.id, EndRequest.Manual))
            if (condition != UnlockCondition.ManualOnly) {
                assertTrue(engine.requestEnd(active.id, EndRequest.ScheduleEnded))
            }
        }
    }

    @Test fun malformedInputCannotBeEnrolledAndLaterAuthorizeAnyPolicy() = runBlocking {
        val before = harness.raw()
        for (input in listOf("", " ", "not-a-tag", "A", "AA:BB")) {
            assertNull("invalid scan input cannot create an enrolled credential",
                tags.enrollTag(input, "Synthetic invalid", null, ""))
            assertNull(tags.getTagForUid(input))
            assertTrue("invalid enrollment must not write state", before == harness.raw())
        }
        assertFalse(protector.isKeyAvailable())
    }

    @Test fun activationWithoutAnEnrolledTagIsRejectedWithoutWrites() = runBlocking {
        profiles.saveProfile(Profile("synthetic-empty", "Synthetic empty", unlockCondition = UnlockCondition.ManualOnly))
        val before = harness.raw()
        assertFalse(engine.tryActivateProfile("synthetic-empty"))
        assertTrue(before == harness.raw())
        assertFalse(engine.enforcementState.value.isBlockingActive)
    }

    @Test fun callerSuppliedEnrollmentBooleanIsNotEngineAuthorization() = runBlocking {
        enroll()
        val active = activate(UnlockCondition.RequireNfcTag(allowAnyEnrolledTag = true))
        assertFalse(engine.requestEnd(active.id, EndRequest.Nfc("synthetic-not-enrolled", true)))
        assertProtected(active)
    }

    @Test fun deletingLastTagAfterEnrollmentReadCannotActivateWithoutCredentials() = runBlocking {
        val tag = enroll()
        profiles.saveProfile(Profile("synthetic-racing-activation", "Synthetic activation"))
        closeEngine()
        openEngine {
            val enrolledAtRead = tags.getTags().isNotEmpty()
            tags.deleteTag(tag.id)
            enrolledAtRead
        }
        assertFalse("activation must recheck enrollment inside its write",
            engine.tryActivateProfile("synthetic-racing-activation"))
        assertTrue(tags.getTags().isEmpty())
        assertNull(profiles.readEnforcementSnapshot().activeProfile)
        assertFalse(profiles.getProfileById("synthetic-racing-activation")!!.isActive)
    }

    @Test fun storageFailureDuringActivationDropsTheCommandAfterRepair() = runBlocking {
        enroll()
        profiles.saveProfile(Profile("synthetic-racing-activation", "Synthetic activation"))
        closeEngine()
        val key = stringPreferencesKey("emergency_recovery_json")
        var failNextRead = true
        openEngine {
            if (failNextRead) {
                failNextRead = false
                harness.store.updateData { it.toMutablePreferences().apply { this[key] = "{invalid" } }
            }
            tags.getTags().isNotEmpty()
        }
        val activating = async { engine.tryActivateProfile("synthetic-racing-activation") }
        withTimeout(10_000) { harness.local.recoveryRequiredFlow.first { it } }
        harness.store.updateData { it.toMutablePreferences().apply { remove(key) } }
        harness.local.retryReadingPersistedState()
        assertFalse("a repaired read must not replay the old activation", activating.await())
        withTimeout(10_000) { engine.enforcementState.first { !it.storageRecoveryRequired } }
        assertNull(profiles.readEnforcementSnapshot().activeProfile)
        assertTrue(engine.tryActivateProfile("synthetic-racing-activation"))
    }

    @Test fun resolvedTapCannotEndAReplacementSessionOfTheSameProfile() = runBlocking {
        val tag = enroll()
        val original = activate(UnlockCondition.RequireNfcTag(tag.id))
        val proposal = resolver.resolve(UID_A) as NfcTagAction.DeactivateProfile
        assertTrue(engine.requestEnd(original.id, EndRequest.ScheduleEnded))
        assertTrue(engine.tryActivateProfile(original.id))
        val replacement = profiles.readEnforcementSnapshot().activeProfile!!
        assertNotEquals(original.sessionId, replacement.sessionId)
        assertFalse("a resolver proposal belongs to the session scanned, not a later activation", endProposal(proposal))
        assertProtected(replacement)
    }

    @Test fun resolvedActivationCannotReplaceALaterProtectedSession() = runBlocking {
        val tag = enroll()
        val linked = Profile("synthetic-linked", "Synthetic linked", linkedTagId = tag.id)
        profiles.saveProfile(linked)
        val proposal = resolver.resolve(UID_A) as NfcTagAction.ActivateProfile
        val active = activate(UnlockCondition.RequireNfcTag(tag.id))
        assertFalse("a stale activation proposal must not replace a later protected session",
            engine.tryActivateProfile(proposal.profile.id))
        assertProtected(active)
    }

    @Test fun sessionReplacementDuringEnrollmentLookupCannotCommitStaleUnlock() = runBlocking {
        val tag = enroll()
        val original = activate(UnlockCondition.RequireNfcTag(tag.id))
        closeEngine()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : NfcTagRepository by tags {
            override suspend fun getTagForUid(rawUid: String): NfcTagRecord? {
                entered.complete(Unit)
                release.await()
                return tags.getTagForUid(rawUid)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engineScope = scope
        engine = EnforcementEngine(profiles, harness.local, scope, nfcTagRepository = delayed)
        withTimeout(10_000) { engine.enforcementState.first { it.activeProfile == original } }
        val ending = async { engine.requestNfcEnd(original, UID_A) }
        entered.await()
        // Adversarial persisted replacement after the engine's read, before its atomic write.
        profiles.setActiveProfile(original.id)
        val replacement = profiles.readEnforcementSnapshot().activeProfile!!
        release.complete(Unit)
        assertFalse(ending.await())
        withTimeout(10_000) { engine.enforcementState.first { it.activeProfile == replacement } }
        assertProtected(replacement)
    }

    @Test fun deletedTagCannotAuthorizeAnEarlierProposal() = runBlocking {
        val tag = enroll()
        val original = activate(UnlockCondition.RequireNfcTag(allowAnyEnrolledTag = true))
        val proposal = resolver.resolve(UID_A) as NfcTagAction.DeactivateProfile
        assertTrue(engine.requestEnd(original.id, EndRequest.ScheduleEnded))
        tags.deleteTag(tag.id)
        assertFalse(endProposal(proposal))
        assertNull(profiles.readEnforcementSnapshot().activeProfile)
    }

    @Test fun lostKeystoreKeyRequiresRecoveryAndExplicitReenrollment() = runBlocking {
        val originalTag = enroll()
        val active = activate(UnlockCondition.RequireNfcTag(originalTag.id, requireIntentionPhrase = false,
            emergencyCooldownMinutes = 1))
        keyStore.deleteEntry(alias)
        assertFalse(protector.isKeyAvailable())
        reload()
        assertNull(tags.getTagForUid(UID_A))
        assertEquals(listOf(originalTag), tags.getTags())
        assertTrue(resolver.resolve(UID_A) is NfcTagAction.UnlockRejected)
        assertProtected(active)
        assertFalse(engine.requestEnd(active.id, EndRequest.Emergency(true, true)))
        assertTrue(engine.startEmergencyUnlock(false))
        elapsed.addAndGet(60_000)
        withTimeout(10_000) { engine.enforcementState.first { !it.isBlockingActive } }
        assertNull(profiles.readEnforcementSnapshot().activeProfile)
        val replacement = checkNotNull(tags.enrollTag(UID_A, "Synthetic renewed", null, ""))
        assertNotEquals(originalTag.id, replacement.id)
        assertTrue("a replacement installation key must not reproduce old fingerprints",
            originalTag.uidFingerprint != replacement.uidFingerprint)
        assertTrue("ordinary re-enrollment must retain, not reinterpret, the old record",
            originalTag in tags.getTags())
        val inactive = profiles.getProfileById(active.id)!!
        val policy = inactive.unlockCondition as UnlockCondition.RequireNfcTag
        assertEquals(originalTag.id, policy.requiredTagId)
        profiles.saveProfile(inactive.copy(linkedTagId = replacement.id,
            unlockCondition = policy.copy(requiredTagId = replacement.id)))
        reload()
        assertEquals(replacement, tags.getTagForUid(UID_A))
        assertTrue(engine.tryActivateProfile(active.id))
        assertTrue(endProposal(resolver.resolve(UID_A) as NfcTagAction.DeactivateProfile))
    }

    @Test fun unusableKeystoreKeyRefusesLookupAndReenrollmentButKeepsEmergencyRecovery() = runBlocking {
        val original = enroll()
        val active = activate(UnlockCondition.RequireNfcTag(original.id, requireIntentionPhrase = false,
            emergencyCooldownMinutes = 1))
        keyStore.deleteEntry(alias)
        // An owned incompatible key exercises actual Android Mac initialization failure.
        // It does not claim to reproduce every vendor's hardware key invalidation mechanism.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        assertTrue(protector.isKeyAvailable())
        val before = harness.raw()
        assertNull(tags.getTagForUid(UID_A))
        assertNull(tags.enrollTag(UID_A, "Synthetic retry", null, "", original.id))
        assertTrue("unusable keys must not rewrite existing credentials", before == harness.raw())
        assertTrue(resolver.resolve(UID_A) is NfcTagAction.UnlockRejected)
        assertProtected(active)
        assertTrue(engine.startEmergencyUnlock(false))
        elapsed.addAndGet(60_000)
        withTimeout(10_000) { engine.enforcementState.first { !it.isBlockingActive } }
        assertNull(tags.enrollTag(UID_A, "Synthetic retry", null, "", original.id))
        assertEquals(listOf(original), tags.getTags())
    }

    @Test fun unreadableStorageDropsScanAndRepairDoesNotReplayIt() = runBlocking {
        val tag = enroll()
        val active = activate(UnlockCondition.RequireNfcTag(tag.id))
        val key = stringPreferencesKey("emergency_recovery_json")
        harness.store.updateData { it.toMutablePreferences().apply { this[key] = "{invalid" } }
        withTimeout(10_000) { harness.local.recoveryRequiredFlow.first { it } }
        assertTrue(resolver.resolve(UID_A) is NfcTagAction.StorageUnavailable)
        assertTrue(engine.isPackageBlocked("invalid.synthetic.unlisted"))
        engine.registerExemptPackage("invalid.synthetic.dialer")
        for (exempt in listOf("com.android.phone", "com.android.telecom", "com.android.emergency",
            "websnag.elopenmike.com", "invalid.synthetic.dialer")) {
            assertFalse(engine.isPackageBlocked(exempt))
        }
        engine.pauseRecoveryLockdown()
        assertFalse(engine.isPackageBlocked("invalid.synthetic.unlisted"))
        assertTrue(engine.isPackageBlocked("invalid.synthetic.distraction"))
        harness.store.updateData { it.toMutablePreferences().apply { remove(key) } }
        harness.local.retryReadingPersistedState()
        withTimeout(10_000) { engine.enforcementState.first { !it.storageRecoveryRequired } }
        assertProtected(active)
        assertTrue(endProposal(resolver.resolve(UID_A) as NfcTagAction.DeactivateProfile))
    }

    @Test fun failureDuringResolutionDropsTapEvenWhenStorageRepairsBeforeTimeout() = runBlocking {
        val tag = enroll()
        val active = activate(UnlockCondition.RequireNfcTag(tag.id))
        val key = stringPreferencesKey("emergency_recovery_json")
        val failingRead = object : ProfileRepository by profiles {
            override suspend fun getProfiles(): List<Profile> {
                harness.store.updateData { it.toMutablePreferences().apply { this[key] = "{invalid" } }
                return profiles.getProfiles()
            }
        }
        val resolving = async {
            NfcActionResolver(failingRead, tags,
                storageGeneration = { harness.local.storageRecoveryState.value.generation }) {
                harness.local.recoveryRequiredFlow.value
            }.resolve(UID_A)
        }
        withTimeout(10_000) { harness.local.recoveryRequiredFlow.first { it } }
        harness.store.updateData { it.toMutablePreferences().apply { remove(key) } }
        harness.local.retryReadingPersistedState()
        assertTrue("a repaired read must not replay a pre-failure tap",
            resolving.await() is NfcTagAction.StorageUnavailable)
        withTimeout(10_000) { engine.enforcementState.first { !it.storageRecoveryRequired } }
        assertProtected(active)
    }

    @Test fun queuedNfcEndCannotCommitAcrossStorageFailureAndRepair() = runBlocking {
        val tag = enroll()
        val active = activate(UnlockCondition.RequireNfcTag(tag.id))
        closeEngine()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pauseNextWrite = true
        val interleaving = object : DataStore<Preferences> by harness.store {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (pauseNextWrite) {
                    pauseNextWrite = false
                    entered.complete(Unit)
                    release.await()
                }
                return harness.store.updateData(transform)
            }
        }
        val local = LocalDataStore(interleaving)
        profiles = DefaultProfileRepository(local)
        tags = DefaultNfcTagRepository(local, protector)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engineScope = scope
        engine = EnforcementEngine(profiles, local, scope, nfcTagRepository = tags)
        withTimeout(10_000) { engine.enforcementState.first { it.activeProfile == active } }
        var endEvents = 0
        val events = launch(start = CoroutineStart.UNDISPATCHED) { engine.endEvents.collect { endEvents++ } }
        try {
            val ending = async { engine.requestNfcEnd(active, UID_A) }
            entered.await()
            val key = stringPreferencesKey("emergency_recovery_json")
            harness.store.updateData { it.toMutablePreferences().apply { this[key] = "{invalid" } }
            withTimeout(10_000) { local.recoveryRequiredFlow.first { it } }
            harness.store.updateData { it.toMutablePreferences().apply { remove(key) } }
            local.retryReadingPersistedState()
            withTimeout(10_000) { local.recoveryRequiredFlow.first { !it } }
            release.complete(Unit)
            assertFalse("a queued pre-failure NFC commit must be invalidated", ending.await())
            withTimeout(10_000) { engine.enforcementState.first { !it.storageRecoveryRequired } }
            yield()
            assertEquals(0, endEvents)
            assertProtected(active)
            assertTrue(engine.requestNfcEnd(active, UID_A))
            yield()
            assertEquals(1, endEvents)
        } finally {
            release.complete(Unit)
            events.cancelAndJoin()
        }
    }

    private companion object {
        const val UID_A = "04A10203040506"
        const val UID_B = "04B10203040506"
        const val UID_UNKNOWN = "04C10203040506"
        const val UID_DELETED = "04F10203040506"
    }
}
