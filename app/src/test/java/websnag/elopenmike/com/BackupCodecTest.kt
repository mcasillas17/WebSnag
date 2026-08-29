package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertThrows
import websnag.elopenmike.com.core.backup.BackupCodec
import websnag.elopenmike.com.core.backup.BackupException
import websnag.elopenmike.com.core.backup.BackupTagMetadata
import websnag.elopenmike.com.core.backup.BackupSnapshot
import websnag.elopenmike.com.core.model.AppThemeMode
import websnag.elopenmike.com.core.model.Profile
import kotlin.experimental.xor

class BackupCodecTest {

    @Test
    fun roundTripsAnEncryptedSnapshotWithTheSamePassphrase() {
        val snapshot = BackupSnapshot(
            profiles = listOf(
                Profile(id = "profile-1", name = "Deep Work", blockedPackages = setOf("com.example.app"))
            ),
            themeMode = AppThemeMode.DARK,
            historyIncluded = false
        )

        val encrypted = BackupCodec.encrypt(snapshot, "correct horse battery staple".toCharArray())
        val restored = BackupCodec.decrypt(encrypted, "correct horse battery staple".toCharArray())

        assertEquals(snapshot, restored)
    }

    @Test
    fun rejectsWrongPassphraseAndModifiedCiphertext() {
        val encrypted = BackupCodec.encrypt(BackupSnapshot(), "correct horse battery staple".toCharArray())

        assertThrows(BackupException.AuthenticationFailed::class.java) {
            BackupCodec.decrypt(encrypted, "wrong passphrase value".toCharArray())
        }
        encrypted[encrypted.lastIndex] = (encrypted.last() xor 1)
        assertThrows(BackupException.AuthenticationFailed::class.java) {
            BackupCodec.decrypt(encrypted, "correct horse battery staple".toCharArray())
        }
    }

    @Test
    fun rejectsUnsupportedVersionBeforeDecrypting() {
        val encrypted = BackupCodec.encrypt(BackupSnapshot(), "correct horse battery staple".toCharArray())
        encrypted[4] = 2

        assertThrows(BackupException.UnsupportedVersion::class.java) {
            BackupCodec.decrypt(encrypted, "correct horse battery staple".toCharArray())
        }
    }

    @Test
    fun permitsTagsWithAnOptionalEmptyDescription() {
        val snapshot = BackupSnapshot(
            tags = listOf(BackupTagMetadata("tag-1", "fingerprint", "Desk tag", 1, description = ""))
        )

        val encrypted = BackupCodec.encrypt(snapshot, "correct horse battery staple".toCharArray())

        assertEquals(snapshot, BackupCodec.decrypt(encrypted, "correct horse battery staple".toCharArray()))
    }

    @Test
    fun roundTripsOnlyTheP1DeviceBoundTagFingerprint() {
        val snapshot = BackupSnapshot(
            tags = listOf(
                BackupTagMetadata(
                    id = "tag-1",
                    uidFingerprint = "device-bound-fingerprint",
                    label = "Desk tag",
                    createdAtEpochMs = 1
                )
            )
        )

        val restored = BackupCodec.decrypt(
            BackupCodec.encrypt(snapshot, "correct horse battery staple".toCharArray()),
            "correct horse battery staple".toCharArray()
        )

        assertEquals("device-bound-fingerprint", restored.tags.single().uidFingerprint)
        assertFalse(restored.tags.single().uidFingerprint.contains("04A1"))
    }
}
