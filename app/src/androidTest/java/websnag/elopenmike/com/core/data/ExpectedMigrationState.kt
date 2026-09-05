package websnag.elopenmike.com.core.data

import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition

/** Explicit protected output expectations; no legacy parsing or migration logic. */
internal object ExpectedMigrationState {
    fun tags(fingerprintA: String, fingerprintB: String, mixed: Boolean = false): List<NfcTagRecord> {
        val first = NfcTagRecord(
            id = "synthetic-tag-a", uidFingerprint = fingerprintA,
            label = "Synthetic \"desk\"\\tag\n雪", customPayload = "synthetic://fixture/only",
            createdAtEpochMs = 1700000000000L, lastUsedEpochMs = 1700000060000L,
            description = "Synthetic\ttext \"quoted\" \\ Ω"
        )
        val second = NfcTagRecord(
            id = "synthetic-tag-b", uidFingerprint = fingerprintB, label = "Synthetic spare",
            customPayload = null, createdAtEpochMs = 1700000001000L, lastUsedEpochMs = null,
            description = ""
        )
        return listOf(first, second) + if (mixed) listOf(first.copy(
            id = "synthetic-current-tag", uidFingerprint = "SYNTHETIC_INSTALLATION_FINGERPRINT_0"
        )) else emptyList()
    }

    fun profiles(mixed: Boolean = false): List<Profile> {
        val active = Profile(
            id = "synthetic-profile-active", name = "Synthetic \"focus\"\n雪",
            description = "Synthetic \\ description", colorHex = "#123456", iconName = "shield",
            filterMode = FilterMode.ALLOWLIST, blockedPackages = setOf("invalid.synthetic.allowed"),
            linkedTagId = "synthetic-tag-a",
            unlockCondition = UnlockCondition.RequireNfcTag(
                requiredTagId = "synthetic-tag-b", allowAnyEnrolledTag = false,
                allowEmergencyUnlock = true, emergencyCooldownMinutes = 17, requireIntentionPhrase = true
            ),
            isActive = true, activatedAtEpochMs = 1700000100000L, triggers = emptyList()
        )
        val inactive = Profile(
            id = "synthetic-profile-inactive", name = "Synthetic idle", description = "",
            colorHex = "#3B82F6", iconName = "shield", filterMode = FilterMode.BLOCKLIST,
            blockedPackages = emptySet(), linkedTagId = null, unlockCondition = UnlockCondition.ManualOnly,
            isActive = false, activatedAtEpochMs = null, triggers = emptyList()
        )
        return listOf(active, inactive) + if (mixed) listOf(active.copy(
            id = "synthetic-current-profile", isActive = false, activatedAtEpochMs = null
        )) else emptyList()
    }
}
