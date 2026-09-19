package websnag.elopenmike.com.core.nfc

import kotlinx.coroutines.withTimeoutOrNull
import websnag.elopenmike.com.core.data.NfcTagRepository
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import java.io.IOException

sealed interface NfcTagAction {
    /**
     * Proposed unlock for this exact profile/session; only the engine can authorize and commit it.
     */
    data class DeactivateProfile(val profile: Profile, val tagUid: String) : NfcTagAction

    /**
     * Proposed activation of a linked profile; resolution does not change active state.
     */
    data class ActivateProfile(val profile: Profile, val tagUid: String) : NfcTagAction

    /**
     * The tag was tapped while a profile is active, but is not authorized to unlock it.
     */
    data class UnlockRejected(val activeProfile: Profile, val tagUid: String) : NfcTagAction

    /**
     * The tag is known and enrolled, but not bound to an active or actionable profile.
     */
    data class EnrolledTagDetected(val tagRecord: NfcTagRecord) : NfcTagAction

    /**
     * The tag is new and not yet enrolled in WebSnag.
     */
    data class UnknownTagDetected(val tagUid: String, val payload: String?) : NfcTagAction

    /**
     * Persisted state could not be read, so the tap was deliberately dropped. Resolving it would
     * otherwise wait for storage recovery and then apply a tap made arbitrarily earlier against the
     * state that finally loaded.
     */
    data object StorageUnavailable : NfcTagAction
}

/**
 * Resolves scanned physical NFC tags to appropriate domain state transitions.
 */
class NfcActionResolver(
    private val profileRepository: ProfileRepository,
    private val nfcTagRepository: NfcTagRepository,
    private val storageGeneration: () -> Long = { 0L },
    private val storageUnreadable: () -> Boolean = { false }
) {
    /**
     * The tap is dropped here rather than at each call site, because there is more than one: the
     * main screen and the block overlay both resolve scans, and the overlay is the surface actually
     * in front of the user while storage recovery is required. Its reads wait for recovery instead
     * of failing, so an unguarded resolve would hold the tap and act on it whenever the state
     * eventually loads.
     */
    suspend fun resolve(scannedUid: String, payload: String? = null): NfcTagAction {
        val generation = storageGeneration()
        if (storageUnreadable()) return NfcTagAction.StorageUnavailable
        // Bounded as well as guarded: on a cold start the first read has not failed yet, so the
        // flag above can still be false while the read is already waiting.
        val loaded = try {
            withTimeoutOrNull(STORAGE_OPERATION_TIMEOUT_MS) {
                val profiles = profileRepository.getProfiles()
                val enrolledTag = nfcTagRepository.getTagForUid(scannedUid)
                if (storageUnreadable() || generation != storageGeneration()) return@withTimeoutOrNull null
                if (enrolledTag != null) nfcTagRepository.recordTagUsage(enrolledTag.id)
                profiles to enrolledTag
            }
        } catch (_: IOException) {
            return NfcTagAction.StorageUnavailable
        } ?: return NfcTagAction.StorageUnavailable
        if (storageUnreadable() || generation != storageGeneration()) return NfcTagAction.StorageUnavailable
        val (profiles, enrolledTag) = loaded
        val activeProfile = profiles.firstOrNull { it.isActive }

        // Scenario 1: A profile is currently active -> attempt to unlock
        if (activeProfile != null) {
            return if (enrolledTag != null && activeProfile.canUnlockWithTag(enrolledTag.id)) {
                NfcTagAction.DeactivateProfile(activeProfile, scannedUid)
            } else {
                NfcTagAction.UnlockRejected(activeProfile, scannedUid)
            }
        }

        // Scenario 2: No profile active -> check if tag is bound to an inactive profile
        val linkedProfile = profiles.firstOrNull { profile ->
            !profile.isActive && enrolledTag != null && profile.linkedTagId == enrolledTag.id
        }

        if (linkedProfile != null) {
            return NfcTagAction.ActivateProfile(linkedProfile, scannedUid)
        }

        // Scenario 3: Tag is enrolled in WebSnag but not linked directly to an auto-toggle profile
        if (enrolledTag != null) {
            return NfcTagAction.EnrolledTagDetected(enrolledTag)
        }

        // Scenario 4: Tag is brand new
        return NfcTagAction.UnknownTagDetected(scannedUid, payload)
    }

    private companion object {
        /** One tap's reads and usage write share a deadline; recovery must never replay it. */
        const val STORAGE_OPERATION_TIMEOUT_MS = 3_000L
    }
}
