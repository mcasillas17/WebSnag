package websnag.elopenmike.com.core.data

import org.junit.Assert.*
import org.junit.Test
import websnag.elopenmike.com.core.backup.*
import websnag.elopenmike.com.core.model.*
import java.nio.ByteBuffer

class BackupFixtureTest {
    private val passphrase get() = "synthetic fixture passphrase only".toCharArray()
    private val profile = Profile("synthetic-profile", "Synthetic profile")
    private val tag = BackupTagMetadata("synthetic-tag", "SYNTHETIC_FINGERPRINT", "Synthetic tag", 1700000000000L)
    private val schedule = ScheduleRecord("synthetic-schedule", "Synthetic schedule", profile.id, profile.name,
        startHour = 9, startMinute = 0, daysOfWeek = setOf(ScheduleDay.MON))
    private fun history(count: Int) = List(count) {
        FocusSessionRecord("synthetic-session-$it", profile.id, profile.name,
            startTimeEpochMs = 1700000000000L, endTimeEpochMs = 1700000060000L, durationSeconds = 60)
    }

    @Test fun freshProductionSaltAndNonceProduceDistinctRoundTrips() {
        val snapshot = BackupSnapshot(listOf(profile), listOf(schedule), listOf(tag), AppThemeMode.DARK, history(501), true, 3650)
        val first = BackupCodec.encrypt(snapshot, passphrase)
        val second = BackupCodec.encrypt(snapshot, passphrase)
        assertFalse(first.copyOfRange(11, 27).contentEquals(second.copyOfRange(11, 27)))
        assertFalse(first.copyOfRange(27, 39).contentEquals(second.copyOfRange(27, 39)))
        assertEquals(snapshot, BackupCodec.decrypt(first, passphrase))
        assertEquals(snapshot, BackupCodec.decrypt(second, passphrase))
    }

    @Test fun storageCapIsNotBackupCountLimit() {
        val accepted = BackupSnapshot(history = history(501), historyIncluded = true)
        assertEquals(501, BackupCodec.decrypt(BackupCodec.encrypt(accepted, passphrase), passphrase).history.size)
        val countError = assertThrows(BackupException.InvalidInput::class.java) {
            BackupCodec.encrypt(BackupSnapshot(history = history(10_001), historyIncluded = true), passphrase)
        }
        assertEquals("Backup has too many records.", countError.message)
        val byteError = assertThrows(BackupException.InvalidInput::class.java) {
            BackupCodec.encrypt(BackupSnapshot(history = history(10_000), historyIncluded = true), passphrase)
        }
        assertEquals("Backup content exceeds the allowed size.", byteError.message)
    }

    @Test fun recordLimitsAndDuplicateIdsAreRejected() {
        val atLimits = BackupSnapshot(
            profiles = List(100) { profile.copy(id = "synthetic-profile-$it") },
            schedules = List(200) { schedule.copy(id = "synthetic-schedule-$it", profileId = "synthetic-profile-0") },
            tags = List(200) { tag.copy(id = "synthetic-tag-$it", uidFingerprint = "SYNTHETIC_FINGERPRINT_$it") })
        assertEquals(atLimits, BackupCodec.decrypt(BackupCodec.encrypt(atLimits, passphrase), passphrase))
        val invalid = listOf(
            atLimits.copy(profiles = atLimits.profiles + profile),
            atLimits.copy(schedules = atLimits.schedules + schedule),
            atLimits.copy(tags = atLimits.tags + tag),
            BackupSnapshot(profiles = listOf(profile, profile)),
            BackupSnapshot(schedules = listOf(schedule, schedule)),
            BackupSnapshot(tags = listOf(tag, tag)),
            BackupSnapshot(history = history(1) + history(1), historyIncluded = true),
            BackupSnapshot(history = history(1), historyIncluded = false),
            BackupSnapshot(historyRetentionDays = 0), BackupSnapshot(historyRetentionDays = 3651),
            BackupSnapshot(profiles = listOf(profile.copy(name = ""))),
            BackupSnapshot(tags = listOf(tag.copy(description = "x".repeat(4097)))))
        invalid.forEachIndexed { index, snapshot ->
            assertThrows("invalid snapshot case $index", BackupException.InvalidInput::class.java) {
                BackupCodec.encrypt(snapshot, passphrase)
            }
        }
    }

    @Test fun invalidScheduleTimeBoundariesAreRejected() {
        listOf(schedule.copy(startHour = -1), schedule.copy(startHour = 24),
            schedule.copy(endHour = -1), schedule.copy(endHour = 24),
            schedule.copy(startMinute = -1), schedule.copy(startMinute = 60),
            schedule.copy(endMinute = -1), schedule.copy(endMinute = 60)).forEach {
            assertThrows(BackupException.InvalidInput::class.java) {
                BackupCodec.encrypt(BackupSnapshot(profiles = listOf(profile), schedules = listOf(it)), passphrase)
            }
        }
    }

    @Test fun malformedEnvelopeBoundariesAreRejected() {
        val envelope = BackupCodec.encrypt(BackupSnapshot(), passphrase)
        val invalid = mutableListOf(ByteArray(0), ByteArray(1_048_577))
        (1..43).forEach { invalid += envelope.copyOf(it) }
        invalid += envelope.copyOf(envelope.size - 1)
        invalid += envelope + byteArrayOf(0)
        listOf(0, 9, 10).forEach { offset -> invalid += envelope.copyOf().apply { this[offset] = 0 } }
        invalid += envelope.copyOf().apply { ByteBuffer.wrap(this).putInt(5, 1) }
        listOf(-1, 0, 15, Int.MAX_VALUE).forEach { length ->
            invalid += envelope.copyOf().apply { ByteBuffer.wrap(this).putInt(39, length) }
        }
        invalid.forEachIndexed { index, value ->
            assertThrows("invalid envelope case $index", BackupException.Malformed::class.java) {
                BackupCodec.decrypt(value, passphrase)
            }
        }
        assertThrows(BackupException.UnsupportedVersion::class.java) {
            BackupCodec.decrypt(envelope.copyOf().apply { this[4] = 127 }, passphrase)
        }
        listOf(11, 27, envelope.lastIndex).forEach { offset ->
            assertThrows(BackupException.AuthenticationFailed::class.java) {
                BackupCodec.decrypt(envelope.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }, passphrase)
            }
        }
    }
    @Test fun duplicateFingerprintsAreRejectedEvenWithDifferentStableIds() {
        assertThrows(BackupException.InvalidInput::class.java) {
            BackupCodec.encrypt(BackupSnapshot(tags = listOf(tag, tag.copy(id = "synthetic-other-id"))), passphrase)
        }
    }

    @Test fun emptyDaysDanglingProfilesAndMissingScheduleTextAreRejected() {
        for (bad in listOf(schedule.copy(daysOfWeek = emptySet()), schedule.copy(profileId = "synthetic-missing"),
            schedule.copy(profileId = ""), schedule.copy(profileName = ""))) {
            assertThrows(BackupException.InvalidInput::class.java) {
                BackupCodec.encrypt(BackupSnapshot(profiles = listOf(profile), schedules = listOf(bad)), passphrase)
            }
        }
    }

}
