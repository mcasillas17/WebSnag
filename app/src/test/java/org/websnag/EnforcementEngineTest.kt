package org.websnag

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
import org.websnag.core.enforcement.EnforcementEngine
import org.websnag.core.model.Profile

@OptIn(ExperimentalCoroutinesApi::class)
class EnforcementEngineTest {

    private lateinit var profileRepo: FakeProfileRepository

    @Before
    fun setup() {
        profileRepo = FakeProfileRepository()
    }

    @Test
    fun testActivationAndPackageBlocking() = runTest {
        val engine = EnforcementEngine(profileRepo, backgroundScope)

        val profile = Profile(
            id = "prof-1",
            name = "Focus",
            blockedPackages = setOf("com.instagram.android", "com.twitter.android"),
            isActive = false
        )
        profileRepo.saveProfile(profile)
        runCurrent()

        assertFalse(engine.enforcementState.value.isBlockingActive)
        assertFalse(engine.isPackageBlocked("com.instagram.android"))

        // Activate profile
        engine.activateProfile("prof-1")
        runCurrent()

        assertTrue(engine.enforcementState.value.isBlockingActive)
        assertEquals("prof-1", engine.enforcementState.value.activeProfile?.id)
        assertTrue(engine.isPackageBlocked("com.instagram.android"))
        assertTrue(engine.isPackageBlocked("com.twitter.android"))
        assertFalse(engine.isPackageBlocked("com.google.android.calculator"))

        // Deactivate profile
        engine.deactivateProfile("prof-1")
        runCurrent()

        assertFalse(engine.enforcementState.value.isBlockingActive)
        assertNull(engine.enforcementState.value.activeProfile)
        assertFalse(engine.isPackageBlocked("com.instagram.android"))
    }

    @Test
    fun testRecordBlockedAttempt() = runTest {
        val engine = EnforcementEngine(profileRepo, backgroundScope)
        engine.recordBlockedAttempt("com.tiktok")
        assertEquals("com.tiktok", engine.enforcementState.value.lastBlockedPackageName)
        assertNotNull(engine.enforcementState.value.lastBlockedEpochMs)
    }

    @Test
    fun testEmergencyUnlockCooldown() = runTest {
        val engine = EnforcementEngine(profileRepo, backgroundScope)
        val profile = Profile(
            id = "prof-emergency",
            name = "Strict",
            blockedPackages = setOf("com.social"),
            isActive = false
        )
        profileRepo.saveProfile(profile)
        engine.activateProfile("prof-emergency")
        runCurrent()

        assertTrue(engine.enforcementState.value.isBlockingActive)

        var completed = false
        // Start 5-minute emergency unlock cooldown
        engine.startEmergencyUnlock(5) {
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

        assertTrue(completed)
        assertFalse(engine.enforcementState.value.emergencyCooldownActive)
        assertFalse(engine.enforcementState.value.isBlockingActive)
    }
}
