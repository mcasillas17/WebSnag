package org.websnag.core.nfc

import org.websnag.core.data.NfcTagRepository
import org.websnag.core.data.ProfileRepository
import org.websnag.core.model.NfcTagRecord
import org.websnag.core.model.Profile

sealed interface NfcTagAction {
    /**
     * The tag unlocked and deactivated an active profile.
     */
    data class DeactivateProfile(val profile: Profile, val tagUid: String) : NfcTagAction

    /**
     * The tag is bound to a profile and activated it.
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
}

/**
 * Resolves scanned physical NFC tags to appropriate domain state transitions.
 */
class NfcActionResolver(
    private val profileRepository: ProfileRepository,
    private val nfcTagRepository: NfcTagRepository
) {
    suspend fun resolve(scannedUid: String, payload: String? = null): NfcTagAction {
        val profiles = profileRepository.getProfiles()
        val activeProfile = profiles.firstOrNull { it.isActive }
        val enrolledTag = nfcTagRepository.getTagByUid(scannedUid)

        // Record tag tap timestamp if enrolled
        if (enrolledTag != null) {
            nfcTagRepository.recordTagUsage(scannedUid)
        }

        // Scenario 1: A profile is currently active -> attempt to unlock
        if (activeProfile != null) {
            return if (activeProfile.canUnlockWithTag(scannedUid)) {
                NfcTagAction.DeactivateProfile(activeProfile, scannedUid)
            } else {
                NfcTagAction.UnlockRejected(activeProfile, scannedUid)
            }
        }

        // Scenario 2: No profile active -> check if tag is bound to an inactive profile
        val linkedProfile = profiles.firstOrNull { profile ->
            !profile.isActive && profile.linkedTagUid.equals(scannedUid, ignoreCase = true)
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
}
