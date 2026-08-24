package websnag.elopenmike.com

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import websnag.elopenmike.com.core.data.NfcTagRepository
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.core.nfc.NfcActionResolver
import websnag.elopenmike.com.core.nfc.NfcTagAction

class FakeProfileRepository : ProfileRepository {
    private val profiles = MutableStateFlow<List<Profile>>(emptyList())
    override val profilesFlow: Flow<List<Profile>> = profiles
    override val activeProfileFlow: Flow<Profile?> = profiles.map { list -> list.firstOrNull { it.isActive } }

    override suspend fun getProfiles(): List<Profile> = profiles.value
    override suspend fun getProfileById(id: String): Profile? = profiles.value.firstOrNull { it.id == id }
    override suspend fun saveProfile(profile: Profile) {
        val list = profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        profiles.value = list
    }
    override suspend fun deleteProfile(id: String) {
        profiles.value = profiles.value.filterNot { it.id == id }
    }
    override suspend fun setActiveProfile(id: String?) {
        profiles.value = profiles.value.map { it.copy(isActive = (it.id == id)) }
    }
    override suspend fun initializeDefaultProfilesIfNeeded() {}
}

class FakeNfcTagRepository : NfcTagRepository {
    private val tags = MutableStateFlow<List<NfcTagRecord>>(emptyList())
    override val tagsFlow: Flow<List<NfcTagRecord>> = tags

    override suspend fun getTags(): List<NfcTagRecord> = tags.value
    override suspend fun getTagByUid(uidHex: String): NfcTagRecord? =
        tags.value.firstOrNull { it.uidHex.equals(uidHex, ignoreCase = true) }
    override suspend fun saveTag(tag: NfcTagRecord) {
        val list = tags.value.toMutableList()
        val idx = list.indexOfFirst { it.id == tag.id || it.uidHex.equals(tag.uidHex, ignoreCase = true) }
        if (idx >= 0) list[idx] = tag else list.add(tag)
        tags.value = list
    }
    override suspend fun deleteTag(id: String) {
        tags.value = tags.value.filterNot { it.id == id }
    }
    override suspend fun recordTagUsage(uidHex: String) {}
}

class NfcActionResolverTest {

    private lateinit var profileRepo: FakeProfileRepository
    private lateinit var tagRepo: FakeNfcTagRepository
    private lateinit var resolver: NfcActionResolver

    @Before
    fun setup() {
        profileRepo = FakeProfileRepository()
        tagRepo = FakeNfcTagRepository()
        resolver = NfcActionResolver(profileRepo, tagRepo)
    }

    @Test
    fun testUnknownTagDetected() = runTest {
        val action = resolver.resolve("UNKNOWN_UID")
        assertTrue(action is NfcTagAction.UnknownTagDetected)
        assertEquals("UNKNOWN_UID", (action as NfcTagAction.UnknownTagDetected).tagUid)
    }

    @Test
    fun testEnrolledTagDetectedWhenNoProfileLinked() = runTest {
        tagRepo.saveTag(
            NfcTagRecord(id = "1", uidHex = "DESK_TAG", label = "Desk")
        )

        val action = resolver.resolve("DESK_TAG")
        assertTrue(action is NfcTagAction.EnrolledTagDetected)
        assertEquals("Desk", (action as NfcTagAction.EnrolledTagDetected).tagRecord.label)
    }

    @Test
    fun testActivateProfileWhenTagTapped() = runTest {
        val profile = Profile(
            id = "p1",
            name = "Deep Focus",
            blockedPackages = setOf("com.instagram.android"),
            linkedTagUid = "DESK_TAG",
            isActive = false
        )
        profileRepo.saveProfile(profile)
        tagRepo.saveTag(NfcTagRecord(id = "1", uidHex = "DESK_TAG", label = "Desk"))

        val action = resolver.resolve("DESK_TAG")
        assertTrue(action is NfcTagAction.ActivateProfile)
        assertEquals("p1", (action as NfcTagAction.ActivateProfile).profile.id)
    }

    @Test
    fun testDeactivateProfileWhenMatchingTagTapped() = runTest {
        val profile = Profile(
            id = "p1",
            name = "Deep Focus",
            blockedPackages = setOf("com.instagram.android"),
            linkedTagUid = "DESK_TAG",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagUid = "DESK_TAG"),
            isActive = true
        )
        profileRepo.saveProfile(profile)

        val action = resolver.resolve("DESK_TAG")
        assertTrue(action is NfcTagAction.DeactivateProfile)
        assertEquals("p1", (action as NfcTagAction.DeactivateProfile).profile.id)
    }

    @Test
    fun testRejectUnlockWhenWrongTagTapped() = runTest {
        val profile = Profile(
            id = "p1",
            name = "Deep Focus",
            blockedPackages = setOf("com.instagram.android"),
            linkedTagUid = "DESK_TAG",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagUid = "DESK_TAG"),
            isActive = true
        )
        profileRepo.saveProfile(profile)

        val action = resolver.resolve("OTHER_TAG")
        assertTrue(action is NfcTagAction.UnlockRejected)
    }
}
