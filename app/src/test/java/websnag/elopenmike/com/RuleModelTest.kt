package websnag.elopenmike.com

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.Trigger
import websnag.elopenmike.com.core.model.UnlockCondition

class RuleModelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testProfileUnlockConditionMatching() {
        val profileWithSpecificTag = Profile(
            id = "test-1",
            name = "Work Profile",
            blockedPackages = setOf("com.instagram.android"),
            linkedTagId = "desk-tag",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = "desk-tag"),
            isActive = true
        )

        // Matching tag
        assertTrue(profileWithSpecificTag.canUnlockWithTag("desk-tag"))

        // Wrong tag
        assertFalse(profileWithSpecificTag.canUnlockWithTag("other-tag"))

        // Profile that is inactive should not unlock
        val inactiveProfile = profileWithSpecificTag.copy(isActive = false)
        assertFalse(inactiveProfile.canUnlockWithTag("desk-tag"))
    }

    @Test
    fun testProfileUnlockConditionAnyTag() {
        val profileWithAnyTag = Profile(
            id = "test-2",
            name = "Relax Profile",
            blockedPackages = setOf("com.slack"),
            linkedTagId = null,
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = null, allowAnyEnrolledTag = true),
            isActive = true
        )

        assertTrue(profileWithAnyTag.canUnlockWithTag("04A1B2C3"))
        assertTrue(profileWithAnyTag.canUnlockWithTag("99887766"))
    }

    @Test
    fun testProfileSerialization() {
        val profile = Profile(
            id = "uuid-123",
            name = "Deep Work",
            description = "High focus session",
            colorHex = "#2563EB",
            blockedPackages = setOf("com.twitter.android", "com.instagram.android"),
            linkedTagId = "desk-tag",
            unlockCondition = UnlockCondition.RequireNfcTag(
                requiredTagId = "desk-tag",
                emergencyCooldownMinutes = 5
            ),
            triggers = listOf(
                Trigger.NfcTag(id = "trig-1", tagId = "desk-tag")
            )
        )

        val serialized = json.encodeToString(profile)
        val deserialized = json.decodeFromString<Profile>(serialized)

        assertEquals(profile.id, deserialized.id)
        assertEquals(profile.name, deserialized.name)
        assertEquals(profile.blockedPackages, deserialized.blockedPackages)
        assertEquals(profile.linkedTagId, deserialized.linkedTagId)
    }
}
