package websnag.elopenmike.com.core.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import java.io.IOException
import java.util.Locale

/** Payload-free failure: parser/key exceptions may contain raw identifiers. Original bytes stay in DataStore. */
internal class LegacyTagMigrationException : IOException("Legacy tag migration could not safely complete. Original preferences were retained.")

/** One historical identity conversion, also used by DataStore before its first read or write. */
internal class LegacyTagIdentifierMigration(private val protector: TagIdentityProtector) : DataMigration<Preferences> {
    private val json = Json { ignoreUnknownKeys = true }
    private val tagsKey = stringPreferencesKey("nfc_tags_json")
    private val profilesKey = stringPreferencesKey("profiles_json")
    private val legacyKeys = setOf("uidHex", "linkedTagUid", "requiredTagUid", "tagUid")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        listOf(currentData[tagsKey], currentData[profilesKey]).any { raw ->
            if (raw == null) false else {
                // Decoded keys cover escaped JSON keys; the textual check also catches truncated legacy input.
                runCatching { containsLegacyKey(json.parseToJsonElement(raw)) }.getOrElse {
                    legacyKeys.any { key -> Regex("\"$key\"\\s*:").containsMatchIn(raw) }
                }
            }
        }

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (!shouldMigrate(currentData)) return currentData
        try {
            val entries = currentData[tagsKey]?.let { json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
            val profiles = currentData[profilesKey]?.let { json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
            val idsByUid = mutableMapOf<String, String>()
            val migratedTags = JsonArray(entries.map { entry ->
                val tag = entry.jsonObject.toMutableMap()
                if (tag.containsKey("uidHex")) {
                    val uid = normalizedUid(requiredString(tag.remove("uidHex")))
                    val id = requiredString(tag["id"])
                    check(idsByUid.put(uid, id) == null)
                    val fingerprint = protectedUid(uid)
                    optionalString(tag["uidFingerprint"])?.let { check(it == fingerprint) }
                    tag["uidFingerprint"] = JsonPrimitive(fingerprint)
                }
                JsonObject(tag)
            })
            val decodedTags = json.decodeFromJsonElement<List<NfcTagRecord>>(migratedTags)
            check(decodedTags.all { it.id.isNotBlank() && it.uidFingerprint.isNotBlank() })
            check(decodedTags.map { it.id }.distinct().size == decodedTags.size)
            check(decodedTags.map { it.uidFingerprint }.distinct().size == decodedTags.size)
            val idsByFingerprint = decodedTags.associate { it.uidFingerprint to it.id }
            fun resolve(value: JsonElement?): String? {
                val raw = optionalString(value) ?: return null
                val uid = normalizedUid(raw)
                return idsByUid[uid] ?: idsByFingerprint[protectedUid(uid)] ?: throw LegacyTagMigrationException()
            }
            fun MutableMap<String, JsonElement>.convert(oldKey: String, newKey: String) {
                if (!containsKey(oldKey)) return
                val resolved = resolve(remove(oldKey))
                val existing = optionalString(this[newKey])
                check(existing == null || existing == resolved)
                this[newKey] = resolved?.let(::JsonPrimitive) ?: JsonNull
            }
            val migratedProfiles = JsonArray(profiles.map { entry ->
                val profile = entry.jsonObject.toMutableMap()
                profile.convert("linkedTagUid", "linkedTagId")
                profile["unlockCondition"]?.let { element ->
                    val condition = element.jsonObject.toMutableMap()
                    val hadLegacyReference = condition.containsKey("requiredTagUid")
                    condition.convert("requiredTagUid", "requiredTagId")
                    if (hadLegacyReference && optionalString(condition["type"]) == REQUIRE_NFC_TYPE) {
                        // Legacy null was implicit-any. Only a current explicit policy may opt into any enrolled tag.
                        condition["allowAnyEnrolledTag"] = JsonPrimitive(false)
                    }
                    profile["unlockCondition"] = JsonObject(condition)
                }
                profile["triggers"]?.let { element ->
                    profile["triggers"] = JsonArray(element.jsonArray.map { trigger ->
                        JsonObject(trigger.jsonObject.toMutableMap().apply { convert("tagUid", "tagId") })
                    })
                }
                JsonObject(profile)
            })
            val decodedProfiles = json.decodeFromJsonElement<List<Profile>>(migratedProfiles)
            check(decodedProfiles.all { it.id.isNotBlank() })
            check(decodedProfiles.map { it.id }.distinct().size == decodedProfiles.size)
            check(!containsLegacyKey(migratedTags) && !containsLegacyKey(migratedProfiles))
            // Construct the replacement only after both collections validate. DataStore commits it atomically.
            return currentData.toMutablePreferences().apply {
                if (currentData[tagsKey] != null) this[tagsKey] = migratedTags.toString()
                if (currentData[profilesKey] != null) this[profilesKey] = migratedProfiles.toString()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            throw LegacyTagMigrationException()
        }
    }

    override suspend fun cleanUp() = Unit

    private fun protectedUid(uid: String): String = protector.fingerprint(uid)?.takeIf { it.isNotBlank() }
        ?: throw LegacyTagMigrationException()

    private fun normalizedUid(raw: String): String = raw.trim().uppercase(Locale.ROOT).also {
        check(it.length in 2..64 && it.length % 2 == 0 && it.all { char -> char in '0'..'9' || char in 'A'..'F' })
    }

    private fun optionalString(value: JsonElement?): String? {
        if (value == null || value == JsonNull) return null
        return requiredString(value)
    }

    private fun requiredString(value: JsonElement?): String {
        check(value is JsonPrimitive && value.isString)
        return value.content.also { check(it.isNotBlank()) }
    }

    private fun containsLegacyKey(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) -> key in legacyKeys || containsLegacyKey(value) }
        is JsonArray -> element.any(::containsLegacyKey)
        else -> false
    }

    private companion object {
        const val REQUIRE_NFC_TYPE = "websnag.elopenmike.com.core.model.UnlockCondition.RequireNfcTag"
    }
}
