package websnag.elopenmike.com.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import websnag.elopenmike.com.core.model.AppThemeMode
import websnag.elopenmike.com.core.model.EmergencyRecovery
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.FocusSessionRecord
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleRecord
import websnag.elopenmike.com.core.backup.BackupSnapshot
import websnag.elopenmike.com.core.backup.BackupTagMetadata
import websnag.elopenmike.com.core.diagnostics.DiagnosticMetadataCodec
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.diagnostics.LocalErrorRecord
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.diagnostics.ScheduleReconciliationRecord

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "websnag_preferences")

/**
 * Decodes a persisted `schedules_json` value into a [ScheduleRecord] list and only re-emits
 * when the decoded schedules actually change.
 *
 * [LocalDataStore.schedulesFlow] shares its single Preferences DataStore with unrelated
 * diagnostics metadata -- most notably `scheduleReconciliation`, which
 * [websnag.elopenmike.com.core.schedule.ScheduleManager.evaluateCurrentSchedules] rewrites on
 * every reconcile pass. Every DataStore write re-emits the whole `Preferences` snapshot to all
 * collectors of `context.dataStore.data`, so without deduping on the decoded value here, a
 * metadata-only write would re-trigger every `schedulesFlow` collector (including
 * [websnag.elopenmike.com.core.schedule.ScheduleManager.start], which reconciles and rewrites
 * the same metadata again) in an infinite loop.
 */
internal fun Flow<String?>.mapRawScheduleJsonToDistinctSchedules(
    json: Json,
    defaultSchedules: () -> List<ScheduleRecord>
): Flow<List<ScheduleRecord>> = map { rawJson ->
    if (rawJson.isNullOrBlank()) {
        defaultSchedules()
    } else {
        try {
            json.decodeFromString(rawJson)
        } catch (e: Exception) {
            defaultSchedules()
        }
    }
}.distinctUntilChanged()

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
    private val activeScheduleOccurrenceKey = stringPreferencesKey("active_schedule_occurrence_json")
    private val emergencyRecoveryKey = stringPreferencesKey("emergency_recovery_json")
    private val scheduleReconciliationKey = stringPreferencesKey("schedule_reconciliation_json")
    private val localErrorKey = stringPreferencesKey("local_error_json")

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

    val activeScheduleOccurrenceFlow: Flow<websnag.elopenmike.com.core.schedule.ScheduleOccurrence?> =
        context.dataStore.data.map { preferences ->
            preferences[activeScheduleOccurrenceKey]?.let { raw ->
                runCatching {
                    json.decodeFromString<websnag.elopenmike.com.core.schedule.ScheduleOccurrence>(raw)
                }.getOrNull()
            }
        }

    val emergencyRecoveryFlow: Flow<EmergencyRecovery?> = context.dataStore.data.map { preferences ->
        preferences[emergencyRecoveryKey]?.let { raw ->
            runCatching { json.decodeFromString<EmergencyRecovery>(raw) }.getOrNull()
        }
    }

    /** Most recent [ScheduleReconciliationRecord], if [evaluateCurrentSchedules][websnag.elopenmike.com.core.schedule.ScheduleManager.evaluateCurrentSchedules] has ever run. */
    val scheduleReconciliationFlow: Flow<ScheduleReconciliationRecord?> = context.dataStore.data.map { preferences ->
        DiagnosticMetadataCodec.decode(preferences[scheduleReconciliationKey])
    }

    /** Most recent [LocalErrorRecord], if any local error has ever been recorded. */
    val localErrorFlow: Flow<LocalErrorRecord?> = context.dataStore.data.map { preferences ->
        DiagnosticMetadataCodec.decode(preferences[localErrorKey])
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

    val schedulesFlow: Flow<List<ScheduleRecord>> = context.dataStore.data
        .map { preferences -> preferences[schedulesKey] }
        .mapRawScheduleJsonToDistinctSchedules(json, ::defaultSchedules)

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
            preferences[focusSessionsKey] = json.encodeToString(
                currentList.filter { it.endTimeEpochMs >= oldestAllowed }.take(MAX_HISTORY_RECORDS)
            )
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
                        uidFingerprint = tag.uidFingerprint,
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
                    uidFingerprint = tag.uidFingerprint,
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
            preferences.remove(activeScheduleOccurrenceKey)
            preferences.remove(emergencyRecoveryKey)
            preferences.remove(scheduleReconciliationKey)
            preferences.remove(localErrorKey)
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
    suspend fun saveActiveScheduleOccurrence(
        occurrence: websnag.elopenmike.com.core.schedule.ScheduleOccurrence?
    ) {
        context.dataStore.edit { preferences ->
            if (occurrence == null) preferences.remove(activeScheduleOccurrenceKey)
            else preferences[activeScheduleOccurrenceKey] = json.encodeToString(occurrence)
        }
    }

    suspend fun saveEmergencyRecovery(recovery: EmergencyRecovery?) {
        context.dataStore.edit { preferences ->
            if (recovery == null) preferences.remove(emergencyRecoveryKey)
            else preferences[emergencyRecoveryKey] = json.encodeToString(recovery)
        }
    }

    /**
     * Persists exactly one typed [ScheduleReconciliationRecord] for the most recent reconciliation
     * pass. [timestampEpochMs] and [outcome] are the only data retained -- no schedule id, profile
     * id, or other payload is ever stored alongside them.
     */
    suspend fun saveScheduleReconciliation(timestampEpochMs: Long, outcome: ReconciliationOutcome) {
        context.dataStore.edit { preferences ->
            preferences[scheduleReconciliationKey] = json.encodeToString(
                ScheduleReconciliationRecord(timestampEpochMs = timestampEpochMs, outcome = outcome)
            )
        }
    }

    /**
     * Persists exactly one typed [LocalErrorRecord] for the most recent local error. [timestampEpochMs]
     * and [category] are the only data retained -- no exception message, stack trace, or other
     * payload is ever stored alongside them.
     */
    suspend fun saveLocalError(timestampEpochMs: Long, category: ErrorCategory) {
        context.dataStore.edit { preferences ->
            preferences[localErrorKey] = json.encodeToString(
                LocalErrorRecord(timestampEpochMs = timestampEpochMs, category = category)
            )
        }
    }

    suspend fun migrateLegacyTagIdentifiers(protector: TagIdentityProtector) {
        context.dataStore.edit { preferences ->
            val rawJson = preferences[nfcTagsKey] ?: return@edit
            val entries = runCatching { json.parseToJsonElement(rawJson) as JsonArray }.getOrNull() ?: return@edit
            if (entries.none { "uidHex" in it.jsonObject }) return@edit
            val migrated = entries.mapNotNull { entry ->
                val objectValue = entry.jsonObject
                val rawUid = objectValue["uidHex"]?.toString()?.trim('"') ?: return@mapNotNull null
                val fingerprint = protector.fingerprint(rawUid) ?: run {
                    preferences.remove(nfcTagsKey)
                    return@edit
                }
                NfcTagRecord(
                    id = objectValue["id"]?.toString()?.trim('"') ?: return@mapNotNull null,
                    uidFingerprint = fingerprint,
                    label = objectValue["label"]?.toString()?.trim('"') ?: "NFC Tag",
                    customPayload = objectValue["customPayload"]?.toString()?.trim('"'),
                    description = objectValue["description"]?.toString()?.trim('"') ?: ""
                )
            }
            preferences[nfcTagsKey] = json.encodeToString(migrated)
            val tagIdsByLegacyUid = entries.mapNotNull { entry ->
                val objectValue = entry.jsonObject
                val uid = objectValue["uidHex"]?.jsonPrimitive?.content
                val id = objectValue["id"]?.jsonPrimitive?.content
                if (uid != null && id != null) uid to id else null
            }.toMap()
            val profileRawJson = preferences[profilesKey] ?: return@edit
            val profileEntries = runCatching { json.parseToJsonElement(profileRawJson) as JsonArray }.getOrNull()
                ?: return@edit
            val migratedProfiles = profileEntries.map { entry ->
                val profile = entry.jsonObject.toMutableMap()
                val linkedId = profile.remove("linkedTagUid")?.jsonPrimitive?.content?.let(tagIdsByLegacyUid::get)
                if (linkedId != null) profile["linkedTagId"] = JsonPrimitive(linkedId)
                val condition = profile["unlockCondition"]?.jsonObject?.toMutableMap()
                if (condition != null) {
                    val requiredId = condition.remove("requiredTagUid")?.jsonPrimitive?.content?.let(tagIdsByLegacyUid::get)
                        ?: linkedId
                    if (requiredId != null) condition["requiredTagId"] = JsonPrimitive(requiredId)
                    profile["unlockCondition"] = JsonObject(condition)
                }
                JsonObject(profile)
            }
            preferences[profilesKey] = JsonArray(migratedProfiles).toString()
        }
    }

    private companion object {
        const val MAX_HISTORY_RECORDS = 500
    }
}
