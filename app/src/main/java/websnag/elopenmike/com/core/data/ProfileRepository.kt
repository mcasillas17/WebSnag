package websnag.elopenmike.com.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.core.model.EmergencyRecovery

/** One coherent persisted session/request pair, used for compare-and-set enforcement writes. */
data class EnforcementSnapshot(val activeProfile: Profile?, val recovery: EmergencyRecovery?)

class ActiveProfileEditException : IllegalStateException("Active profiles cannot be edited. End the session first.")

/**
 * Repository interface for managing distraction blocking profiles.
 */
interface ProfileRepository {
    val profilesFlow: Flow<List<Profile>>
    val activeProfileFlow: Flow<Profile?>
    val enforcementSnapshotFlow: Flow<EnforcementSnapshot>
    suspend fun readEnforcementSnapshot(): EnforcementSnapshot

    suspend fun getProfiles(): List<Profile>
    suspend fun getProfileById(id: String): Profile?
    suspend fun saveProfile(profile: Profile)
    suspend fun deleteProfile(id: String)
    suspend fun setActiveProfile(id: String?)
    suspend fun compareAndSetEnforcement(expected: EnforcementSnapshot, updated: EnforcementSnapshot): Boolean
    suspend fun initializeDefaultProfilesIfNeeded()
}

class DefaultProfileRepository(
    private val localDataStore: LocalDataStore
) : ProfileRepository {

    override val profilesFlow: Flow<List<Profile>> = localDataStore.profilesFlow

    override val enforcementSnapshotFlow = localDataStore.enforcementSnapshotFlow
    override val activeProfileFlow: Flow<Profile?> = localDataStore.activeProfileFlow
    override suspend fun readEnforcementSnapshot() = localDataStore.readEnforcementSnapshot()

    override suspend fun compareAndSetEnforcement(expected: EnforcementSnapshot, updated: EnforcementSnapshot) =
        localDataStore.compareAndSetEnforcement(expected, updated)

    override suspend fun getProfiles(): List<Profile> {
        return localDataStore.profilesFlow.first()
    }

    override suspend fun getProfileById(id: String): Profile? {
        return getProfiles().firstOrNull { it.id == id }
    }

    override suspend fun saveProfile(profile: Profile) {
        localDataStore.saveProfile(profile)
    }

    override suspend fun deleteProfile(id: String) {
        localDataStore.deleteProfileAndSchedules(id)
    }

    override suspend fun setActiveProfile(id: String?) {
        localDataStore.setActiveProfile(id)
    }

    override suspend fun initializeDefaultProfilesIfNeeded() {
        val current = getProfiles()
        if (current.isEmpty()) {
            val defaultProfiles = listOf(
                Profile(
                    id = "profile-deep-work",
                    name = "Deep Work",
                    description = "Eliminate social media and entertainment distractions during focus sessions.",
                    colorHex = "#2563EB",
                    iconName = "work",
                    blockedPackages = setOf(
                        "com.instagram.android",
                        "com.zhiliaoapp.musically", // TikTok
                        "com.twitter.android",
                        "com.facebook.katana",
                        "com.reddit.frontpage",
                        "com.google.android.youtube"
                    ),
                    unlockCondition = UnlockCondition.ManualOnly
                ),
                Profile(
                    id = "profile-bedtime",
                    name = "Bedtime Rest",
                    description = "Wind down and prevent late-night screen scrolling in the bedroom.",
                    colorHex = "#7C3AED",
                    iconName = "bedtime",
                    blockedPackages = setOf(
                        "com.instagram.android",
                        "com.zhiliaoapp.musically",
                        "com.twitter.android",
                        "com.facebook.katana",
                        "com.reddit.frontpage",
                        "com.google.android.youtube",
                        "com.netflix.mediaclient"
                    ),
                    unlockCondition = UnlockCondition.ManualOnly
                )
            )
            localDataStore.saveProfiles(defaultProfiles)
        }
    }
}
