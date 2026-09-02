package websnag.elopenmike.com.core.diagnostics

import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import java.security.MessageDigest

/**
 * Pure input for [DiagnosticsReportFactory]. Deliberately carries only the fields the exported
 * report is allowed to reflect (plus [activeProfile]/[requiredEnrolledTag] purely so the factory
 * can derive a redacted alias / presence flag from them). No caller-supplied field here may be
 * copied verbatim into the report except after sanitization.
 */
data class DiagnosticsFactoryInput(
    val nowEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val buildType: BuildTypeCategory,
    val androidApiLevel: Int,
    val manufacturer: String,
    val model: String,
    val nfcHardwarePresent: Boolean,
    val nfcHardwareEnabled: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val accessibilityServiceRunning: Boolean,
    val notificationPermissionState: PermissionState,
    val exactAlarmCapability: PermissionState,
    val batteryOptimizationState: BatteryOptimizationState,
    val nextScheduledTransitionEpochMs: Long?,
    val nextScheduledTransitionTiming: ScheduledTimingMode,
    val lastScheduleReconciliationEpochMs: Long?,
    val lastScheduleReconciliationOutcome: ReconciliationOutcome,
    /** Used only to derive [ActiveProfileAlias]; its id, name, description, etc. are never copied. */
    val activeProfile: Profile?,
    /** Used only to derive [TagPresenceState]; its id, fingerprint, label, etc. are never copied. */
    val requiredEnrolledTag: NfcTagRecord?,
    val enrolledTagRequired: Boolean,
    val keystoreKeyAvailable: Boolean,
    val backupSchemaVersion: Int,
    val lastErrorCategory: ErrorCategory,
    val lastErrorEpochMs: Long?,
    val remediationActions: List<RemediationAction> = emptyList()
)

/**
 * Builds a [DiagnosticsReport] from a [DiagnosticsFactoryInput]. This is a pure, deterministic
 * transformation with no I/O and no Android dependency, so it is exercised directly by JVM unit
 * tests. It is the sole intended construction path for production diagnostics reports: it is
 * responsible for sanitizing display strings, redacting the active profile identity down to a
 * deterministic alias, collapsing tag identity down to a presence flag, and bounding the
 * remediation action list.
 */
object DiagnosticsReportFactory {

    fun create(input: DiagnosticsFactoryInput): DiagnosticsReport {
        return DiagnosticsReport(
            generatedAtEpochMs = input.nowEpochMs,
            appBuildInfo = AppBuildInfo(
                versionName = sanitizeDisplayString(input.appVersionName),
                versionCode = input.appVersionCode,
                buildType = input.buildType
            ),
            deviceInfo = DeviceInfo(
                apiLevel = input.androidApiLevel,
                manufacturer = sanitizeDisplayString(input.manufacturer),
                model = sanitizeDisplayString(input.model)
            ),
            nfcHardwareState = NfcHardwareState(
                present = input.nfcHardwarePresent,
                enabled = input.nfcHardwareEnabled
            ),
            accessibilityServiceState = AccessibilityServiceState(
                enabled = input.accessibilityServiceEnabled,
                running = input.accessibilityServiceRunning
            ),
            notificationPermissionState = input.notificationPermissionState,
            exactAlarmCapability = input.exactAlarmCapability,
            batteryOptimizationState = input.batteryOptimizationState,
            nextScheduledTransition = NextScheduledTransition(
                epochMs = input.nextScheduledTransitionEpochMs,
                timingMode = input.nextScheduledTransitionTiming
            ),
            scheduleReconciliationStatus = ScheduleReconciliationStatus(
                lastReconciledEpochMs = input.lastScheduleReconciliationEpochMs,
                outcome = input.lastScheduleReconciliationOutcome
            ),
            activeProfileAlias = ActiveProfileAlias(input.activeProfile?.id?.let(::deriveProfileAlias)),
            requiredEnrolledTagStatus = when {
                !input.enrolledTagRequired -> TagPresenceState.NOT_REQUIRED
                input.requiredEnrolledTag != null -> TagPresenceState.PRESENT
                else -> TagPresenceState.MISSING
            },
            keystoreKeyAvailable = input.keystoreKeyAvailable,
            backupSchemaVersion = input.backupSchemaVersion,
            lastError = LastErrorInfo(
                category = input.lastErrorCategory,
                occurredAtEpochMs = input.lastErrorEpochMs
            ),
            remediationActions = input.remediationActions
                .distinct()
                .take(DIAGNOSTICS_MAX_REMEDIATION_ACTIONS)
        )
    }

    /**
     * Derives a deterministic, one-way, redacted alias for a profile id. The same id always
     * yields the same alias, but the alias cannot be reversed back into the original id.
     */
    internal fun deriveProfileAlias(profileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(DIAGNOSTICS_PROFILE_ALIAS_HEX_LENGTH)
        return "$DIAGNOSTICS_PROFILE_ALIAS_PREFIX$hex"
    }

    /**
     * Sanitizes an externally sourced display string (e.g. device manufacturer/model, app version
     * name). Strings containing control characters or path-like separators are redacted entirely
     * rather than partially leaked; otherwise the string is truncated to the max display length.
     */
    internal fun sanitizeDisplayString(raw: String): String {
        val isUnsafe = raw.any { it.isISOControl() } || raw.contains('/') || raw.contains('\\')
        val safe = if (isUnsafe) DIAGNOSTICS_REDACTED_PLACEHOLDER else raw
        return safe.take(DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH)
    }
}
