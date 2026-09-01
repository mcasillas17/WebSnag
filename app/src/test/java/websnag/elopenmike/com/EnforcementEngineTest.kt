package websnag.elopenmike.com

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition

@OptIn(ExperimentalCoroutinesApi::class)
class EnforcementEngineTest {

    private lateinit var profileRepo: FakeProfileRepository

    @Before
    fun setup() {
        profileRepo = FakeProfileRepository()
    }

    @Test
    fun testActivationAndPackageBlocking() = runTest {
        val engine = EnforcementEngine(
            profileRepository = profileRepo,
            coroutineScope = backgroundScope,
            hasEnrolledNfcTag = { true }
        )

        val profile = Profile(
            id = "prof-1",
            name = "Focus",
            blockedPackages = setOf("com.instagram.android", "com.twitter.android"),
            unlockCondition = UnlockCondition.ManualOnly,
            isActive = false
        )
        profileRepo.saveProfile(profile)
        runCurrent()

        assertFalse(engine.enforcementState.value.isBlockingActive)
        assertFalse(engine.isPackageBlocked("com.instagram.android"))

        // Activate profile
        val activationSucceeded = engine.tryActivateProfile("prof-1")
        runCurrent()

        assertTrue(activationSucceeded)
        assertTrue(engine.enforcementState.value.isBlockingActive)
        assertEquals("prof-1", engine.enforcementState.value.activeProfile?.id)
        assertTrue(engine.isPackageBlocked("com.instagram.android"))
        assertTrue(engine.isPackageBlocked("com.twitter.android"))
        assertFalse(engine.isPackageBlocked("com.google.android.calculator"))

        // Deactivate profile
        engine.requestEnd("prof-1", EndRequest.Manual)
        runCurrent()

        assertFalse(engine.enforcementState.value.isBlockingActive)
        assertNull(engine.enforcementState.value.activeProfile)
        assertFalse(engine.isPackageBlocked("com.instagram.android"))
    }

    @Test
    fun `profile activation is rejected when no NFC tags are enrolled`() = runTest {
        val engine = EnforcementEngine(profileRepository = profileRepo, coroutineScope = backgroundScope)
        val profile = Profile(
            id = "prof-no-tags",
            name = "No tags",
            blockedPackages = setOf("com.social"),
            unlockCondition = UnlockCondition.ManualOnly
        )
        profileRepo.saveProfile(profile)
        runCurrent()

        val activationSucceeded = engine.tryActivateProfile(profile.id)
        runCurrent()

        assertFalse(activationSucceeded)
        assertFalse(engine.enforcementState.value.isBlockingActive)
        assertNull(engine.enforcementState.value.activeProfile)
    }

    @Test
    fun testRecordBlockedAttempt() = runTest {
        val engine = EnforcementEngine(profileRepository = profileRepo, coroutineScope = backgroundScope)
        engine.recordBlockedAttempt("com.tiktok")
        assertEquals("com.tiktok", engine.enforcementState.value.lastBlockedPackageName)
        assertNotNull(engine.enforcementState.value.lastBlockedEpochMs)
    }

    @Test
    fun testEmergencyUnlockCooldown() = runTest {
        val engine = EnforcementEngine(
            profileRepository = profileRepo,
            coroutineScope = backgroundScope,
            hasEnrolledNfcTag = { true }
        )
        val profile = Profile(
            id = "prof-emergency",
            name = "Strict",
            blockedPackages = setOf("com.social"),
            isActive = false
        )
        profileRepo.saveProfile(profile)
        engine.tryActivateProfile("prof-emergency")
        runCurrent()

        assertTrue(engine.enforcementState.value.isBlockingActive)

        var completed = false
        // Start 5-minute emergency unlock cooldown
        engine.startEmergencyUnlock(intentionConfirmed = true) {
            completed = true
        }
        runCurrent()

        assertTrue(engine.enforcementState.value.emergencyCooldownActive)
        assertFalse(completed)

        // Advance time by 4 minutes (not yet done)
        advanceTimeBy(4 * 60 * 1000L)
        runCurrent()
        assertTrue(engine.enforcementState.value.emergencyCooldownActive)
        assertFalse(completed)

        // Advance remaining 1 minute + delta
        advanceTimeBy(1 * 60 * 1000L + 100)
        runCurrent()

        assertFalse(engine.enforcementState.value.emergencyCooldownActive)
        assertFalse(engine.enforcementState.value.isBlockingActive)
    }

    @Test
    fun testAllowlistModeBlockingAndExemptions() = runTest {
        val engine = EnforcementEngine(
            profileRepository = profileRepo,
            coroutineScope = backgroundScope,
            hasEnrolledNfcTag = { true }
        )
        engine.registerExemptPackage("com.android.launcher3")

        val allowlistProfile = Profile(
            id = "prof-allowlist",
            name = "Dumbphone Mode",
            filterMode = websnag.elopenmike.com.core.model.FilterMode.ALLOWLIST,
            blockedPackages = setOf("com.google.android.apps.maps", "com.google.android.dialer"),
            isActive = false
        )
        profileRepo.saveProfile(allowlistProfile)
        engine.tryActivateProfile("prof-allowlist")
        runCurrent()

        assertTrue(engine.enforcementState.value.isBlockingActive)
        assertEquals(websnag.elopenmike.com.core.model.FilterMode.ALLOWLIST, engine.enforcementState.value.filterMode)

        // Allowed essentials must NOT be blocked
        assertFalse(engine.isPackageBlocked("com.google.android.apps.maps"))

        // Unlisted apps MUST be blocked in Allowlist mode
        assertTrue(engine.isPackageBlocked("com.instagram.android"))
        assertTrue(engine.isPackageBlocked("com.twitter.android"))
        assertTrue(engine.isPackageBlocked("com.reddit.frontpage"))

        // Critical system packages must NEVER be blocked
        assertFalse(engine.isPackageBlocked("websnag.elopenmike.com"))
        assertFalse(engine.isPackageBlocked("com.android.systemui"))
        assertFalse(engine.isPackageBlocked("com.android.launcher3"))
    }

    @Test
    fun testSessionTimerTracking() = runTest {
        val engine = EnforcementEngine(
            profileRepository = profileRepo,
            coroutineScope = backgroundScope,
            hasEnrolledNfcTag = { true }
        )
        val profile = Profile(
            id = "prof-timer",
            name = "Work",
            blockedPackages = setOf("com.social"),
            unlockCondition = UnlockCondition.ManualOnly,
            isActive = false
        )
        profileRepo.saveProfile(profile)
        assertNull(engine.enforcementState.value.sessionStartedAtEpochMs)

        engine.tryActivateProfile("prof-timer")
        runCurrent()

        assertNotNull(engine.enforcementState.value.sessionStartedAtEpochMs)
        assertTrue(engine.enforcementState.value.elapsedSessionMillis >= 0)

        engine.requestEnd("prof-timer", EndRequest.Manual)
        runCurrent()

        assertNull(engine.enforcementState.value.sessionStartedAtEpochMs)
        assertEquals(0L, engine.enforcementState.value.elapsedSessionMillis)
    }
}
