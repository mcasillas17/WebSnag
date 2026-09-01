package websnag.elopenmike.com.core.diagnostics

import kotlinx.serialization.Serializable

/**
 * Current schema version for [DiagnosticsReport]. Bump whenever the shape of the exported
 * report changes so consumers (support tooling, bug reports) can detect incompatible payloads.
 */
const val DIAGNOSTICS_SCHEMA_VERSION: Int = 1

/** Hard upper bound on the UTF-8 byte size of an exported diagnostics report. */
const val DIAGNOSTICS_MAX_EXPORT_BYTES: Int = 16_384

/** Hard upper bound on the character length of any externally sourced display string. */
const val DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH: Int = 80

/** Hard upper bound on the number of remediation actions a report may surface. */
const val DIAGNOSTICS_MAX_REMEDIATION_ACTIONS: Int = 5

/** Placeholder substituted for a display string rejected as unsafe (control chars or path-like). */
internal const val DIAGNOSTICS_REDACTED_PLACEHOLDER: String = "REDACTED"

/** Fixed prefix for deterministic, redacted active-profile aliases. Never a real profile id. */
internal const val DIAGNOSTICS_PROFILE_ALIAS_PREFIX: String = "profile-"

/** Length, in lowercase hex characters, of the alias suffix appended after [DIAGNOSTICS_PROFILE_ALIAS_PREFIX]. */
internal const val DIAGNOSTICS_PROFILE_ALIAS_HEX_LENGTH: Int = 12

/**
 * Coarse build variant of the running app. Never carries a raw signing/build string.
 */
@Serializable
enum class BuildTypeCategory { DEBUG, RELEASE, OTHER }

/**
 * Tri-state result for an Android runtime permission or OS capability that WebSnag depends on.
 * NOT_REQUIRED means the current OS version / configuration makes the permission moot.
 */
@Serializable
enum class PermissionState { GRANTED, DENIED, NOT_REQUIRED }

/**
 * Whether an enrolled NFC tag required by the active unlock policy is present, missing, or
 * not required at all (e.g. manual-only unlock). Never derived from or paired with a fingerprint.
 */
@Serializable
enum class TagPresenceState { PRESENT, MISSING, NOT_REQUIRED }

/** Coarse battery-optimization posture reported by the OS for this app. */
@Serializable
enum class BatteryOptimizationState { EXEMPTED, RESTRICTED, UNKNOWN }

/** How precisely the next scheduled profile transition can be honored by the OS scheduler. */
@Serializable
enum class ScheduledTimingMode { EXACT, BEST_EFFORT, NOT_SCHEDULED }

/**
 * Outcome of the most recent schedule reconciliation pass. The three "nothing was activated"
 * reasons are kept distinct rather than collapsed into [NO_CHANGE] so diagnostics can tell apart
 * a user dismissal from a data problem (missing profile) from a policy refusal (e.g. a required
 * NFC tag not enrolled) -- never carrying the schedule id, profile id, or tag identity itself.
 */
@Serializable
enum class ReconciliationOutcome {
    ACTIVATED,
    KEPT_ACTIVE,
    ENDED,
    /** The due occurrence was already dismissed by the user; activation was not attempted. */
    DISMISSED_CURRENT_OCCURRENCE,
    /** Activation was attempted but the scheduled profile id no longer resolves to a profile. */
    PROFILE_NOT_FOUND,
    /** Activation was attempted for a resolved profile but the enforcement engine refused it. */
    ACTIVATION_REJECTED,
    NO_CHANGE,
    NEVER_RUN
}

/**
 * Typed category for the last local error observed, deliberately excluding any payload.
 * [DIAGNOSTICS] covers failures collecting, encoding, or writing the diagnostics report itself
 * (see [websnag.elopenmike.com.core.diagnostics.DiagnosticsRepository.currentReport] and
 * [websnag.elopenmike.com.core.diagnostics.DiagnosticsJsonExporter.export]) -- distinct from
 * [BACKUP]/[STORAGE], which cover the pre-existing encrypted-backup and generic local-document
 * read/write failures.
 */
@Serializable
enum class ErrorCategory { NONE, NFC, SCHEDULE, BACKUP, PERMISSION, STORAGE, DIAGNOSTICS, UNKNOWN }

/**
 * A remediation the user can take from within Android settings. Only actions the OS actually
 * exposes for the current version/state are ever included.
 */
@Serializable
enum class RemediationAction {
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_BATTERY_OPTIMIZATION_SETTINGS,
    OPEN_EXACT_ALARM_SETTINGS,
    ENABLE_NFC,
    ENROLL_REQUIRED_TAG,
    RETRY_KEYSTORE_KEY_GENERATION,
    /**
     * Navigate to the existing NFC Hub/enrollment screen. This is the sole remediation surfaced
     * for a missing required enrolled tag and/or an unavailable NFC HMAC Keystore key: there is
     * no direct "generate the key" user action, since (re-)enrolling a tag there is what creates
     * the key as a side effect.
     */
    OPEN_NFC_HUB
}

/**
 * App build identity. [versionName] is expected to already be sanitized/truncated by
 * [websnag.elopenmike.com.core.diagnostics.DiagnosticsReportFactory]; this model does not itself
 * enforce that bound so the exporter's own size guard can be exercised independently in tests.
 */
@Serializable
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Long,
    val buildType: BuildTypeCategory
)

/**
 * Device identity. [manufacturer]/[model] are expected to already be sanitized/truncated display
 * strings produced by the factory; see [AppBuildInfo] for why this model does not re-enforce the
 * length bound itself.
 */
@Serializable
data class DeviceInfo(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String
)

/** Whether NFC radio hardware exists on this device and whether it is currently enabled. */
@Serializable
data class NfcHardwareState(val present: Boolean, val enabled: Boolean)

/** Whether the WebSnag accessibility service is enabled in settings and actively running. */
@Serializable
data class AccessibilityServiceState(val enabled: Boolean, val running: Boolean)

/** Next known profile transition, and how reliably the OS can honor its timing. */
@Serializable
data class NextScheduledTransition(
    val epochMs: Long?,
    val timingMode: ScheduledTimingMode
)

/** Result and timestamp of the most recent schedule reconciliation pass, if any has run. */
@Serializable
data class ScheduleReconciliationStatus(
    val lastReconciledEpochMs: Long?,
    val outcome: ReconciliationOutcome
)

/**
 * Deterministic, redacted stand-in for the active profile's identity. [alias] is derived from
 * (never equal to) the profile id and is null when no profile is active. Never a name or raw id.
 */
@Serializable
data class ActiveProfileAlias(val alias: String?) {
    init {
        require(alias == null || alias.matches(Regex("^${Regex.escape(DIAGNOSTICS_PROFILE_ALIAS_PREFIX)}[0-9a-f]{$DIAGNOSTICS_PROFILE_ALIAS_HEX_LENGTH}$"))) {
            "alias must be null or match the fixed profile alias shape."
        }
    }
}

/** Typed category and timestamp for the last local error, deliberately excluding any payload. */
@Serializable
data class LastErrorInfo(
    val category: ErrorCategory,
    val occurredAtEpochMs: Long?
)

/**
 * Fixed-shape, serializable snapshot of local diagnostics state for the "Protection diagnostics"
 * card. Every field is typed (enum/value object) rather than a free-form string, and no field may
 * carry raw/HMAC NFC ids, profile or tag names/descriptions, package lists, Wi-Fi identifiers,
 * secrets, activity history, or event content. See [websnag.elopenmike.com.core.diagnostics.DiagnosticsReportFactory]
 * for the sole intended construction path and [websnag.elopenmike.com.core.diagnostics.DiagnosticsJsonExporter]
 * for the sole intended export path.
 */
@Serializable
data class DiagnosticsReport(
    val schemaVersion: Int = DIAGNOSTICS_SCHEMA_VERSION,
    val generatedAtEpochMs: Long,
    val appBuildInfo: AppBuildInfo,
    val deviceInfo: DeviceInfo,
    val nfcHardwareState: NfcHardwareState,
    val accessibilityServiceState: AccessibilityServiceState,
    val notificationPermissionState: PermissionState,
    val exactAlarmCapability: PermissionState,
    val batteryOptimizationState: BatteryOptimizationState,
    val nextScheduledTransition: NextScheduledTransition,
    val scheduleReconciliationStatus: ScheduleReconciliationStatus,
    val activeProfileAlias: ActiveProfileAlias,
    val requiredEnrolledTagStatus: TagPresenceState,
    val keystoreKeyAvailable: Boolean,
    val backupSchemaVersion: Int,
    val lastError: LastErrorInfo,
    val remediationActions: List<RemediationAction> = emptyList()
) {
    init {
        require(schemaVersion == DIAGNOSTICS_SCHEMA_VERSION) {
            "schemaVersion must equal $DIAGNOSTICS_SCHEMA_VERSION but was $schemaVersion."
        }
        require(remediationActions.size <= DIAGNOSTICS_MAX_REMEDIATION_ACTIONS) {
            "remediationActions must contain at most $DIAGNOSTICS_MAX_REMEDIATION_ACTIONS entries."
        }
    }

    companion object {
        /** Number of top-level fields in the serialized report shape. Kept in sync by tests. */
        const val FIXED_FIELD_COUNT: Int = 17
    }
}
