package websnag.elopenmike.com.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import websnag.elopenmike.com.core.model.AppThemeMode
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.FocusSessionRecord
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleRecord
import websnag.elopenmike.com.core.backup.BackupSnapshot
import websnag.elopenmike.com.core.backup.BackupTagMetadata

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
    private val historyRetentionDaysKey = intPreferencesKey("history_retention_days")

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

    val historyRetentionDaysFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[historyRetentionDaysKey] ?: BackupSnapshot.DEFAULT_HISTORY_RETENTION_DAYS
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
            val retentionDays = preferences[historyRetentionDaysKey] ?: BackupSnapshot.DEFAULT_HISTORY_RETENTION_DAYS
            val oldestAllowed = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
            preferences[focusSessionsKey] = json.encodeToString(currentList.filter { it.endTimeEpochMs >= oldestAllowed })
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

    suspend fun createBackupSnapshot(includeHistory: Boolean): BackupSnapshot {
        return context.dataStore.data.first().let { preferences ->
            BackupSnapshot(
                profiles = decodeList<Profile>(preferences[profilesKey]),
                schedules = decodeList<ScheduleRecord>(preferences[schedulesKey]),
                tags = decodeList<NfcTagRecord>(preferences[nfcTagsKey]).map { tag ->
                    BackupTagMetadata(
                        id = tag.id,
                        uidHex = tag.uidHex,
                        label = tag.label,
                        createdAtEpochMs = tag.createdAtEpochMs,
                        lastUsedEpochMs = tag.lastUsedEpochMs,
                        description = tag.description
                    )
                },
                themeMode = preferences[themeModeKey]?.let {
                    try {
                        AppThemeMode.valueOf(it)
                    } catch (_: IllegalArgumentException) {
                        AppThemeMode.SYSTEM
                    }
                } ?: AppThemeMode.SYSTEM,
                history = if (includeHistory) decodeList<FocusSessionRecord>(preferences[focusSessionsKey]) else emptyList(),
                historyIncluded = includeHistory,
                historyRetentionDays = preferences[historyRetentionDaysKey] ?: BackupSnapshot.DEFAULT_HISTORY_RETENTION_DAYS
            )
        }
    }

    suspend fun replaceFromBackupIfNoActiveProfile(snapshot: BackupSnapshot): Boolean {
        var restored = false
        context.dataStore.edit { preferences ->
            val activeId = preferences[activeProfileIdKey]
            val hasActiveProfile = decodeList<Profile>(preferences[profilesKey]).any { it.isActive }
            if (activeId != null || hasActiveProfile) return@edit
            preferences[profilesKey] = json.encodeToString(
                snapshot.profiles.map { it.copy(isActive = false, activatedAtEpochMs = null) }
            )
            preferences[schedulesKey] = json.encodeToString(snapshot.schedules)
            preferences[nfcTagsKey] = json.encodeToString(snapshot.tags.map { tag ->
                NfcTagRecord(
                    id = tag.id,
                    uidHex = tag.uidHex,
                    label = tag.label,
                    createdAtEpochMs = tag.createdAtEpochMs,
                    lastUsedEpochMs = tag.lastUsedEpochMs,
                    description = tag.description
                )
            })
            preferences[themeModeKey] = snapshot.themeMode.name
            preferences[historyRetentionDaysKey] = snapshot.historyRetentionDays
            if (snapshot.historyIncluded) {
                preferences[focusSessionsKey] = json.encodeToString(snapshot.history)
            } else {
                preferences.remove(focusSessionsKey)
            }
            preferences.remove(activeProfileIdKey)
            restored = true
        }
        return restored
    }

    suspend fun deleteFocusHistory() {
        context.dataStore.edit { preferences -> preferences.remove(focusSessionsKey) }
    }

    suspend fun deleteAllUserData() {
        context.dataStore.edit { preferences ->
            preferences.remove(profilesKey)
            preferences.remove(nfcTagsKey)
            preferences.remove(activeProfileIdKey)
            preferences.remove(themeModeKey)
            preferences.remove(focusSessionsKey)
            preferences.remove(schedulesKey)
            preferences.remove(historyRetentionDaysKey)
        }
    }

    suspend fun setHistoryRetentionDays(days: Int) {
        require(days in 1..3650) { "Retention must be between one day and ten years." }
        context.dataStore.edit { preferences -> preferences[historyRetentionDaysKey] = days }
    }

    private inline fun <reified T> decodeList(rawJson: String?): List<T> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(rawJson)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
