package websnag.elopenmike.com.core.backup

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.ScheduleRecord
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCodec {
    private val magic = byteArrayOf('W'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 1)
    private const val VERSION = 1
    private const val PBKDF2_ITERATIONS = 210_000
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_ENVELOPE_BYTES = 1_048_576
    private const val MAX_PLAINTEXT_BYTES = 786_432
    private const val MAX_PROFILES = 100
    private const val MAX_SCHEDULES = 200
    private const val MAX_TAGS = 200
    private const val MAX_HISTORY = 10_000
    private const val MAX_STRING_LENGTH = 4_096

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encrypt(snapshot: BackupSnapshot, passphrase: CharArray): ByteArray {
        validateSnapshot(snapshot)
        requirePassphrase(passphrase)
        val plaintext = json.encodeToString(snapshot).encodeToByteArray()
        if (plaintext.size > MAX_PLAINTEXT_BYTES) {
            throw BackupException.InvalidInput("Backup content exceeds the allowed size.")
        }

        val salt = ByteArray(SALT_LENGTH).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        try {
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                doFinal(plaintext)
            }
            return ByteBuffer.allocate(magic.size + 1 + Int.SIZE_BYTES + 2 + salt.size + nonce.size + Int.SIZE_BYTES + ciphertext.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(magic)
                .put(VERSION.toByte())
                .putInt(PBKDF2_ITERATIONS)
                .put(SALT_LENGTH.toByte())
                .put(NONCE_LENGTH.toByte())
                .put(salt)
                .put(nonce)
                .putInt(ciphertext.size)
                .put(ciphertext)
                .array()
        } finally {
            plaintext.fill(0)
            key.fill(0)
        }
    }

    fun decrypt(envelope: ByteArray, passphrase: CharArray): BackupSnapshot {
        requirePassphrase(passphrase)
        if (envelope.size !in 1..MAX_ENVELOPE_BYTES) {
            throw BackupException.Malformed("Backup size is outside the allowed range.")
        }
        val parsed = parseEnvelope(envelope)
        val key = deriveKey(passphrase, parsed.salt, parsed.iterations)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, parsed.nonce))
                doFinal(parsed.ciphertext)
            }
        } catch (_: GeneralSecurityException) {
            throw BackupException.AuthenticationFailed()
        } finally {
            key.fill(0)
        }
        try {
            if (plaintext.size > MAX_PLAINTEXT_BYTES) {
                throw BackupException.Malformed("Backup content exceeds the allowed size.")
            }
            val snapshot = json.decodeFromString<BackupSnapshot>(plaintext.decodeToString())
            validateSnapshot(snapshot)
            return snapshot
        } catch (exception: BackupException) {
            throw exception
        } catch (exception: Exception) {
            throw BackupException.Malformed("Backup content is not valid.", exception)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun parseEnvelope(envelope: ByteArray): ParsedEnvelope {
        try {
            val buffer = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
            val receivedMagic = ByteArray(magic.size)
            buffer.get(receivedMagic)
            if (!receivedMagic.contentEquals(magic)) {
                throw BackupException.Malformed("This file is not a WebSnag backup.")
            }
            val version = buffer.get().toInt() and 0xff
            if (version != VERSION) throw BackupException.UnsupportedVersion(version)
            val iterations = buffer.int
            if (iterations != PBKDF2_ITERATIONS) {
                throw BackupException.Malformed("Unsupported key derivation parameters.")
            }
            val saltLength = buffer.get().toInt() and 0xff
            val nonceLength = buffer.get().toInt() and 0xff
            if (saltLength != SALT_LENGTH || nonceLength != NONCE_LENGTH) {
                throw BackupException.Malformed("Backup encryption parameters are invalid.")
            }
            if (buffer.remaining() < saltLength + nonceLength + Int.SIZE_BYTES) {
                throw BackupException.Malformed("Backup envelope is truncated.")
            }
            val salt = ByteArray(saltLength).also(buffer::get)
            val nonce = ByteArray(nonceLength).also(buffer::get)
            val ciphertextLength = buffer.int
            if (ciphertextLength !in 16..buffer.remaining() || ciphertextLength != buffer.remaining()) {
                throw BackupException.Malformed("Backup ciphertext length is invalid.")
            }
            return ParsedEnvelope(salt, nonce, ByteArray(ciphertextLength).also(buffer::get), iterations)
        } catch (exception: BackupException) {
            throw exception
        } catch (exception: BufferUnderflowException) {
            throw BackupException.Malformed("Backup envelope is truncated.", exception)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (exception: GeneralSecurityException) {
            throw BackupException.Malformed("This device cannot derive a backup key.", exception)
        } finally {
            spec.clearPassword()
        }
    }

    private fun requirePassphrase(passphrase: CharArray) {
        if (passphrase.size < 12) {
            throw BackupException.InvalidInput("Use a passphrase with at least 12 characters.")
        }
    }

    private fun validateSnapshot(snapshot: BackupSnapshot) {
        if (snapshot.profiles.size > MAX_PROFILES || snapshot.schedules.size > MAX_SCHEDULES ||
            snapshot.tags.size > MAX_TAGS || snapshot.history.size > MAX_HISTORY
        ) {
            throw BackupException.InvalidInput("Backup has too many records.")
        }
        if (snapshot.history.isNotEmpty() && !snapshot.historyIncluded) {
            throw BackupException.InvalidInput("History must be explicitly included.")
        }
        if (snapshot.historyRetentionDays !in 1..3650) {
            throw BackupException.InvalidInput("History retention is outside the allowed range.")
        }
        requireUnique(snapshot.profiles.map(Profile::id), "profile")
        requireUnique(snapshot.schedules.map(ScheduleRecord::id), "schedule")
        requireUnique(snapshot.tags.map(BackupTagMetadata::id), "tag")
        requireUnique(snapshot.history.map { it.id }, "history record")
        snapshot.profiles.forEach { profile ->
            requireText(profile.id, "profile ID")
            requireText(profile.name, "profile name")
            profile.blockedPackages.forEach { requireText(it, "package name") }
        }
        snapshot.schedules.forEach { schedule ->
            requireText(schedule.id, "schedule ID")
            requireText(schedule.name, "schedule name")
            if (schedule.startHour !in 0..23 || schedule.endHour !in 0..23 ||
                schedule.startMinute !in 0..59 || schedule.endMinute !in 0..59
            ) throw BackupException.InvalidInput("Schedule time is invalid.")
        }
        snapshot.tags.forEach { tag ->
            requireText(tag.id, "tag ID")
            requireText(tag.uidHex, "tag UID")
            requireText(tag.label, "tag label")
            requireText(tag.description, "tag description")
        }
    }

    private fun requireUnique(ids: List<String>, type: String) {
        if (ids.size != ids.toSet().size) throw BackupException.InvalidInput("Backup contains duplicate $type IDs.")
    }

    private fun requireText(value: String, label: String) {
        if (value.isBlank() || value.length > MAX_STRING_LENGTH) {
            throw BackupException.InvalidInput("$label is invalid.")
        }
    }

    private data class ParsedEnvelope(
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val iterations: Int
    )
}
