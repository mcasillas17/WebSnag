package org.websnag.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.websnag.core.model.Profile
import org.websnag.core.model.UnlockCondition
import java.util.UUID

/**
 * Repository interface for managing distraction blocking profiles.
 */
interface ProfileRepository {
    val profilesFlow: Flow<List<Profile>>
    val activeProfileFlow: Flow<Profile?>

    suspend fun getProfiles(): List<Profile>
    suspend fun getProfileById(id: String): Profile?
    suspend fun saveProfile(profile: Profile)
    suspend fun deleteProfile(id: String)
    suspend fun setActiveProfile(id: String?)
    suspend fun initializeDefaultProfilesIfNeeded()
}

class DefaultProfileRepository(
    private val localDataStore: LocalDataStore
) : ProfileRepository {

    override val profilesFlow: Flow<List<Profile>> = localDataStore.profilesFlow

    override val activeProfileFlow: Flow<Profile?> = combine(
        localDataStore.profilesFlow,
        localDataStore.activeProfileIdFlow
    ) { profiles, activeId ->
        profiles.firstOrNull { it.id == activeId && it.isActive }
    }

    override suspend fun getProfiles(): List<Profile> {
        return localDataStore.profilesFlow.first()
    }

    override suspend fun getProfileById(id: String): Profile? {
        return getProfiles().firstOrNull { it.id == id }
    }

    override suspend fun saveProfile(profile: Profile) {
        val current = getProfiles().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile
        } else {
            current.add(profile)
        }
        localDataStore.saveProfiles(current)
    }

    override suspend fun deleteProfile(id: String) {
        val current = getProfiles().filterNot { it.id == id }
        localDataStore.saveProfiles(current)
        if (localDataStore.activeProfileIdFlow.first() == id) {
            localDataStore.setActiveProfileId(null)
        }
    }

    override suspend fun setActiveProfile(id: String?) {
        val current = getProfiles().map { profile ->
            if (profile.id == id) {
                profile.copy(isActive = true, activatedAtEpochMs = System.currentTimeMillis())
            } else {
                profile.copy(isActive = false, activatedAtEpochMs = null)
            }
        }
        localDataStore.saveProfiles(current)
        localDataStore.setActiveProfileId(id)
    }

    override suspend fun initializeDefaultProfilesIfNeeded() {
        val current = getProfiles()
        if (current.isEmpty()) {
            val defaultProfiles = listOf(
                Profile(
                    id = UUID.randomUUID().toString(),
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
                    unlockCondition = UnlockCondition.RequireNfcTag(
                        allowEmergencyUnlock = true,
                        emergencyCooldownMinutes = 5
                    )
                ),
                Profile(
                    id = UUID.randomUUID().toString(),
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
                    unlockCondition = UnlockCondition.RequireNfcTag(
                        allowEmergencyUnlock = true,
                        emergencyCooldownMinutes = 5
                    )
                )
            )
            localDataStore.saveProfiles(defaultProfiles)
        }
    }
}
