package websnag.elopenmike.com.core.data

import android.content.Context
import androidx.datastore.core.DataMigration
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

internal fun webSnagPreferenceMigrations(protector: TagIdentityProtector): List<DataMigration<Preferences>> =
    listOf(LegacyTagIdentifierMigration(protector))

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "websnag_preferences",
    produceMigrations = { webSnagPreferenceMigrations(AndroidKeystoreTagIdentityProtector()) }
)

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
class LocalDataStore internal constructor(
    private val store: DataStore<Preferences>,
    private val historyNowEpochMs: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(context.dataStore)

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

    val themeModeFlow: Flow<AppThemeMode> = store.data.map { preferences ->
        preferences[themeModeKey]?.let {
            try {
                AppThemeMode.valueOf(it)
            } catch (e: Exception) {
                AppThemeMode.SYSTEM
            }
        } ?: AppThemeMode.SYSTEM
    }

    val profilesFlow: Flow<List<Profile>> = store.data.map { preferences ->
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

    val nfcTagsFlow: Flow<List<NfcTagRecord>> = store.data.map { preferences ->
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
        store.data.map { preferences ->
            preferences[activeScheduleOccurrenceKey]?.let { raw ->
                runCatching {
                    json.decodeFromString<websnag.elopenmike.com.core.schedule.ScheduleOccurrence>(raw)
                }.getOrNull()
            }
        }

    val emergencyRecoveryFlow: Flow<EmergencyRecovery?> = store.data.map { preferences ->
        preferences[emergencyRecoveryKey]?.let { raw ->
            runCatching { json.decodeFromString<EmergencyRecovery>(raw) }.getOrNull()
        }
    }

    /** Most recent [ScheduleReconciliationRecord], if [evaluateCurrentSchedules][websnag.elopenmike.com.core.schedule.ScheduleManager.evaluateCurrentSchedules] has ever run. */
    val scheduleReconciliationFlow: Flow<ScheduleReconciliationRecord?> = store.data.map { preferences ->
        DiagnosticMetadataCodec.decode(preferences[scheduleReconciliationKey])
    }

    /** Most recent [LocalErrorRecord], if any local error has ever been recorded. */
    val localErrorFlow: Flow<LocalErrorRecord?> = store.data.map { preferences ->
        DiagnosticMetadataCodec.decode(preferences[localErrorKey])
    }

    val focusSessionsFlow: Flow<List<FocusSessionRecord>> = store.data.map { preferences ->
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

    val schedulesFlow: Flow<List<ScheduleRecord>> = store.data
        .map { preferences -> preferences[schedulesKey] }
        .mapRawScheduleJsonToDistinctSchedules(json, ::defaultSchedules)

    val activeProfileIdFlow: Flow<String?> = store.data.map { preferences ->
        preferences[activeProfileIdKey]
    }

    val historyRetentionDaysFlow: Flow<Int> = store.data.map { preferences ->
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
        store.edit { preferences ->
            preferences[profilesKey] = json.encodeToString(profiles)
        }
    }

    internal suspend fun deleteProfileAndSchedules(profileId: String) {
        store.edit { preferences ->
            // Decode before changing either collection; corrupt input must not be discarded here.
            val profiles = preferences[profilesKey]?.let { json.decodeFromString<List<Profile>>(it) }.orEmpty()
            check(profiles.none { it.id == profileId && it.isActive } && preferences[activeProfileIdKey] != profileId) {
                "Active profiles cannot be deleted. End the session first."
            }
            val schedules = preferences[schedulesKey]?.let { json.decodeFromString<List<ScheduleRecord>>(it) }
                ?: defaultSchedules().filter { schedule -> profiles.any { it.id == schedule.profileId } }
            preferences[profilesKey] = json.encodeToString(profiles.filterNot { it.id == profileId })
            preferences[schedulesKey] = json.encodeToString(schedules.filterNot { it.profileId == profileId })
        }
    }

    private fun validateTagIdentities(tags: List<NfcTagRecord>) {
        require(tags.all { it.id.isNotBlank() && it.uidFingerprint.isNotBlank() }) { "Tag identity is empty." }
        require(tags.map { it.id }.distinct().size == tags.size) { "Tag IDs are ambiguous." }
        require(tags.map { it.uidFingerprint }.distinct().size == tags.size) { "Tag fingerprints are ambiguous." }
    }

    suspend fun saveNfcTags(tags: List<NfcTagRecord>) {
        validateTagIdentities(tags)
        store.edit { preferences ->
            preferences[nfcTagsKey] = json.encodeToString(tags)
        }
    }

    suspend fun saveFocusSession(record: FocusSessionRecord) {
        store.edit { preferences ->
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
            val oldestAllowed = historyNowEpochMs() - retentionDays * 24L * 60L * 60L * 1000L
            preferences[focusSessionsKey] = json.encodeToString(
                currentList.filter { it.endTimeEpochMs >= oldestAllowed }.take(MAX_HISTORY_RECORDS)
            )
        }
    }

    suspend fun saveSchedule(schedule: ScheduleRecord): Boolean {
        var saved = false
        store.edit { preferences ->
            val profileIds = decodeList<Profile>(preferences[profilesKey]).map { it.id }.toSet()
            // A profile can be deleted after the editor reads it. Refuse that stale save atomically.
            if (schedule.profileId !in profileIds) return@edit
            val rawJson = preferences[schedulesKey]
            val currentList: MutableList<ScheduleRecord> = if (rawJson.isNullOrBlank()) {
                defaultSchedules().filter { it.profileId in profileIds }.toMutableList()
            } else {
                try {
                    json.decodeFromString<List<ScheduleRecord>>(rawJson).toMutableList()
                } catch (e: Exception) {
                    defaultSchedules().filter { it.profileId in profileIds }.toMutableList()
                }
            }
            val existingIndex = currentList.indexOfFirst { it.id == schedule.id }
            if (existingIndex >= 0) {
                currentList[existingIndex] = schedule
            } else {
                currentList.add(schedule)
            }
            preferences[schedulesKey] = json.encodeToString(currentList)
            saved = true
        }
        return saved
    }

    suspend fun toggleSchedule(scheduleId: String, isEnabled: Boolean) {
        store.edit { preferences ->
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
        store.edit { preferences ->
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
        store.edit { preferences ->
            if (profileId == null) {
                preferences.remove(activeProfileIdKey)
            } else {
                preferences[activeProfileIdKey] = profileId
            }
        }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        store.edit { preferences ->
            preferences[themeModeKey] = mode.name
        }
    }

    suspend fun createBackupSnapshot(includeHistory: Boolean): BackupSnapshot {
        return store.data.first().let { preferences ->
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
        val tags = snapshot.tags.map { tag ->
            NfcTagRecord(
                id = tag.id,
                uidFingerprint = tag.uidFingerprint,
                label = tag.label,
                createdAtEpochMs = tag.createdAtEpochMs,
                lastUsedEpochMs = tag.lastUsedEpochMs,
                description = tag.description
            )
        }
        validateTagIdentities(tags)

        var restored = false
        store.edit { preferences ->
            val activeId = preferences[activeProfileIdKey]
            val hasActiveProfile = decodeList<Profile>(preferences[profilesKey]).any { it.isActive }
            if (activeId != null || hasActiveProfile) return@edit
            preferences[profilesKey] = json.encodeToString(
                snapshot.profiles.map { it.copy(isActive = false, activatedAtEpochMs = null) }
            )
            preferences[schedulesKey] = json.encodeToString(snapshot.schedules)
            preferences[nfcTagsKey] = json.encodeToString(tags)
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
        store.edit { preferences -> preferences.remove(focusSessionsKey) }
    }

    suspend fun deleteAllUserData() {
        store.edit { preferences ->
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
        store.edit { preferences -> preferences[historyRetentionDaysKey] = days }
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
        store.edit { preferences ->
            if (occurrence == null) preferences.remove(activeScheduleOccurrenceKey)
            else preferences[activeScheduleOccurrenceKey] = json.encodeToString(occurrence)
        }
    }

    suspend fun saveEmergencyRecovery(recovery: EmergencyRecovery?) {
        store.edit { preferences ->
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
        store.edit { preferences ->
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
        store.edit { preferences ->
            preferences[localErrorKey] = json.encodeToString(
                LocalErrorRecord(timestampEpochMs = timestampEpochMs, category = category)
            )
        }
    }

    suspend fun migrateLegacyTagIdentifiers(protector: TagIdentityProtector) {
        val migration = LegacyTagIdentifierMigration(protector)
        store.updateData { migration.migrate(it) }
    }

    private companion object {
        const val MAX_HISTORY_RECORDS = 500
    }
}
