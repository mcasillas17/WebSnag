package org.websnag.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.websnag.core.model.NfcTagRecord
import org.websnag.core.model.Profile

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "websnag_preferences")

/**
 * Local-first persistent storage using Android DataStore and Kotlinx Serialization.
 */
class LocalDataStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val profilesKey = stringPreferencesKey("profiles_json")
    private val nfcTagsKey = stringPreferencesKey("nfc_tags_json")
    private val activeProfileIdKey = stringPreferencesKey("active_profile_id")

    val profilesFlow: Flow<List<Profile>> = context.dataStore.data.map { preferences ->
        val rawJson = preferences[profilesKey]
        if (rawJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(rawJson)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    val nfcTagsFlow: Flow<List<NfcTagRecord>> = context.dataStore.data.map { preferences ->
        val rawJson = preferences[nfcTagsKey]
        if (rawJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(rawJson)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    val activeProfileIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[activeProfileIdKey]
    }

    suspend fun saveProfiles(profiles: List<Profile>) {
        context.dataStore.edit { preferences ->
            preferences[profilesKey] = json.encodeToString(profiles)
        }
    }

    suspend fun saveNfcTags(tags: List<NfcTagRecord>) {
        context.dataStore.edit { preferences ->
            preferences[nfcTagsKey] = json.encodeToString(tags)
        }
    }

    suspend fun setActiveProfileId(profileId: String?) {
        context.dataStore.edit { preferences ->
            if (profileId == null) {
                preferences.remove(activeProfileIdKey)
            } else {
                preferences[activeProfileIdKey] = profileId
            }
        }
    }
}
