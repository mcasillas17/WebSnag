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
import org.websnag.core.model.AppThemeMode
import org.websnag.core.model.FilterMode
import org.websnag.core.model.FocusSessionRecord
import org.websnag.core.model.NfcTagRecord
import org.websnag.core.model.Profile
import org.websnag.core.model.ScheduleDay
import org.websnag.core.model.ScheduleRecord

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
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val focusSessionsKey = stringPreferencesKey("focus_sessions_json")
    private val schedulesKey = stringPreferencesKey("schedules_json")

    val themeModeFlow: Flow<AppThemeMode> = context.dataStore.data.map { preferences ->
        preferences[themeModeKey]?.let {
            try {
                AppThemeMode.valueOf(it)
            } catch (e: Exception) {
                AppThemeMode.SYSTEM
            }
        } ?: AppThemeMode.SYSTEM
    }

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

    val focusSessionsFlow: Flow<List<FocusSessionRecord>> = context.dataStore.data.map { preferences ->
        val rawJson = preferences[focusSessionsKey]
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

    val schedulesFlow: Flow<List<ScheduleRecord>> = context.dataStore.data.map { preferences ->
        val rawJson = preferences[schedulesKey]
        if (rawJson.isNullOrBlank()) {
            defaultSchedules()
        } else {
            try {
                json.decodeFromString(rawJson)
            } catch (e: Exception) {
                defaultSchedules()
            }
        }
    }

    val activeProfileIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[activeProfileIdKey]
    }

    private fun defaultSchedules(): List<ScheduleRecord> = listOf(
        ScheduleRecord(
            id = "sched-workday",
            name = "Workday Deep Focus",
            profileId = "profile-deep-work",
            profileName = "Deep Work",
            filterMode = FilterMode.ALLOWLIST,
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI),
            isEnabled = false
        ),
        ScheduleRecord(
            id = "sched-bedtime",
            name = "Nightly Wind Down",
            profileId = "profile-bedtime",
            profileName = "Bedtime Rest",
            filterMode = FilterMode.BLOCKLIST,
            startHour = 22,
            startMinute = 30,
            endHour = 7,
            endMinute = 0,
            daysOfWeek = setOf(ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI, ScheduleDay.SAT, ScheduleDay.SUN),
            isEnabled = false
        )
    )

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

    suspend fun saveFocusSession(record: FocusSessionRecord) {
        context.dataStore.edit { preferences ->
            val rawJson = preferences[focusSessionsKey]
            val currentList: MutableList<FocusSessionRecord> = if (rawJson.isNullOrBlank()) {
                mutableListOf()
            } else {
                try {
                    json.decodeFromString<List<FocusSessionRecord>>(rawJson).toMutableList()
                } catch (e: Exception) {
                    mutableListOf()
                }
            }
            currentList.add(0, record) // newest first
            preferences[focusSessionsKey] = json.encodeToString(currentList)
        }
    }

    suspend fun saveSchedule(schedule: ScheduleRecord) {
        context.dataStore.edit { preferences ->
            val rawJson = preferences[schedulesKey]
            val currentList: MutableList<ScheduleRecord> = if (rawJson.isNullOrBlank()) {
                defaultSchedules().toMutableList()
            } else {
                try {
                    json.decodeFromString<List<ScheduleRecord>>(rawJson).toMutableList()
                } catch (e: Exception) {
                    defaultSchedules().toMutableList()
                }
            }
            val existingIndex = currentList.indexOfFirst { it.id == schedule.id }
            if (existingIndex >= 0) {
                currentList[existingIndex] = schedule
            } else {
                currentList.add(schedule)
            }
            preferences[schedulesKey] = json.encodeToString(currentList)
        }
    }

    suspend fun toggleSchedule(scheduleId: String, isEnabled: Boolean) {
        context.dataStore.edit { preferences ->
            val rawJson = preferences[schedulesKey]
            val currentList: MutableList<ScheduleRecord> = if (rawJson.isNullOrBlank()) {
                defaultSchedules().toMutableList()
            } else {
                try {
                    json.decodeFromString<List<ScheduleRecord>>(rawJson).toMutableList()
                } catch (e: Exception) {
                    defaultSchedules().toMutableList()
                }
            }
            val idx = currentList.indexOfFirst { it.id == scheduleId }
            if (idx >= 0) {
                currentList[idx] = currentList[idx].copy(isEnabled = isEnabled)
                preferences[schedulesKey] = json.encodeToString(currentList)
            }
        }
    }

    suspend fun deleteSchedule(scheduleId: String) {
        context.dataStore.edit { preferences ->
            val rawJson = preferences[schedulesKey]
            val currentList: MutableList<ScheduleRecord> = if (rawJson.isNullOrBlank()) {
                defaultSchedules().toMutableList()
            } else {
                try {
                    json.decodeFromString<List<ScheduleRecord>>(rawJson).toMutableList()
                } catch (e: Exception) {
                    defaultSchedules().toMutableList()
                }
            }
            currentList.removeAll { it.id == scheduleId }
            preferences[schedulesKey] = json.encodeToString(currentList)
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

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[themeModeKey] = mode.name
        }
    }
}
