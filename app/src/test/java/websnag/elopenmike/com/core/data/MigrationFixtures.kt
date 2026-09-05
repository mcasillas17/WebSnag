package websnag.elopenmike.com.core.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64

internal object MigrationFixtures {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(name: String): Preferences {
        val fixture = json.parseToJsonElement(checkNotNull(javaClass.getResource("/migrations/v1/$name.json")).readText()).jsonObject
        check(fixture.getValue("fixtureVersion").jsonPrimitive.int == 1)
        return mutablePreferencesOf().apply {
            fixture.getValue("preferences").jsonObject.forEach { (key, value) ->
                when {
                    key == "history_retention_days" -> this[intPreferencesKey(key)] = value.jsonPrimitive.int
                    key.endsWith("_json") -> this[stringPreferencesKey(key)] = value.toString()
                    else -> this[stringPreferencesKey(key)] = value.jsonPrimitive.content
                }
            }
            fixture["rawOverrides"]?.jsonObject?.forEach { (key, value) ->
                this[stringPreferencesKey(key)] = value.jsonPrimitive.content
            }
        }
    }
    // Deterministic identity fake only; Android tests separately exercise real Keystore HMAC.
    val protector = object : TagIdentityProtector {
        override fun fingerprint(rawUid: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(rawUid.trim().uppercase().toByteArray()))
    }
}
