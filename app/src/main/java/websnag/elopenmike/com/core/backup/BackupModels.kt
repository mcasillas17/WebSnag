package websnag.elopenmike.com.core.backup

import kotlinx.serialization.Serializable
import websnag.elopenmike.com.core.model.AppThemeMode
import websnag.elopenmike.com.core.model.FocusSessionRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.ScheduleRecord

@Serializable
data class BackupTagMetadata(
    val id: String,
    val uidHex: String,
    val label: String,
    val createdAtEpochMs: Long,
    val lastUsedEpochMs: Long? = null,
    val description: String = ""
)

@Serializable
data class BackupSnapshot(
    val profiles: List<Profile> = emptyList(),
    val schedules: List<ScheduleRecord> = emptyList(),
    val tags: List<BackupTagMetadata> = emptyList(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val history: List<FocusSessionRecord> = emptyList(),
    val historyIncluded: Boolean = false,
    val historyRetentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS
) {
    companion object {
        const val DEFAULT_HISTORY_RETENTION_DAYS = 90
    }
}

sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidInput(message: String) : BackupException(message)
    class UnsupportedVersion(val version: Int) : BackupException("Unsupported backup version: $version")
    class AuthenticationFailed : BackupException("The passphrase is incorrect or the backup was modified.")
    class Malformed(message: String, cause: Throwable? = null) : BackupException(message, cause)
}
