package websnag.elopenmike.com.core.diagnostics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import websnag.elopenmike.com.core.backup.BackupCodec
import websnag.elopenmike.com.core.data.NfcTagRepository
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.ScheduleRecord
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.core.schedule.ScheduleTransitionCalculator

/**
 * Assembles a [DiagnosticsReport] from a one-time [DiagnosticsStateSource] platform snapshot plus
 * the committed [ProfileRepository]/[NfcTagRepository]/persisted-metadata flows, then hands the
 * result to [DiagnosticsReportFactory] -- the sole place any of these signals is sanitized/redacted
 * before becoming part of the exported report. This class itself never bypasses that factory: it
 * only ever assembles a [DiagnosticsFactoryInput].
 *
 * Every pure derivation (which fields map to which remediation, tag requirement, timing mode,
 * notification tri-state) lives in the companion so it is directly unit-testable on the JVM
 * without faking any Android type.
 *
 * A missing persisted reconciliation/error record ([scheduleReconciliationFlow]/[localErrorFlow]
 * emitting `null`) is treated as legitimate "never happened" state ([ReconciliationOutcome.NEVER_RUN]
 * / [ErrorCategory.NONE] with a `null` timestamp), never as a failure.
 */
class DiagnosticsRepository(
    private val stateSource: DiagnosticsStateSource,
    private val profileRepository: ProfileRepository,
    private val nfcTagRepository: NfcTagRepository,
    private val schedulesFlow: Flow<List<ScheduleRecord>>,
    private val scheduleReconciliationFlow: Flow<ScheduleReconciliationRecord?>,
    private val localErrorFlow: Flow<LocalErrorRecord?>,
    private val backupSchemaVersion: Int = BackupCodec.VERSION,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Builds a fresh [DiagnosticsReport] from the current platform/repository state. */
    suspend fun currentReport(): DiagnosticsReport {
        val snapshot = stateSource.snapshot()
        val activeProfile = profileRepository.activeProfileFlow.first()
        val tags = nfcTagRepository.tagsFlow.first()
        val schedules = schedulesFlow.first()
        val reconciliation = scheduleReconciliationFlow.first()
        val lastError = localErrorFlow.first()
        val now = clock()

        val requiredTagId = requiredTagId(activeProfile)
        val enrolledTagRequired = requiredTagId != null
        val requiredTag = requiredTagId?.let { id -> tags.firstOrNull { it.id == id } }

        val nextTransitionEpochMs = ScheduleTransitionCalculator.nextTransitionEpochMs(schedules, now)
        val notificationPermissionState = notificationPermissionState(
            snapshot.androidApiLevel,
            snapshot.notificationPermissionGranted
        )
        val exactAlarmCapability = if (snapshot.exactAlarmAvailable) PermissionState.GRANTED else PermissionState.DENIED
        val batteryOptimizationState = if (snapshot.batteryOptimizationIgnored) {
            BatteryOptimizationState.EXEMPTED
        } else {
            BatteryOptimizationState.RESTRICTED
        }

        val input = DiagnosticsFactoryInput(
            nowEpochMs = now,
            appVersionName = snapshot.appVersionName,
            appVersionCode = snapshot.appVersionCode,
            buildType = snapshot.buildType,
            androidApiLevel = snapshot.androidApiLevel,
            manufacturer = snapshot.manufacturer,
            model = snapshot.model,
            nfcHardwarePresent = snapshot.nfcHardwarePresent,
            nfcHardwareEnabled = snapshot.nfcHardwareEnabled,
            accessibilityServiceEnabled = snapshot.accessibilityServiceEnabled,
            accessibilityServiceRunning = snapshot.accessibilityServiceRunning,
            notificationPermissionState = notificationPermissionState,
            exactAlarmCapability = exactAlarmCapability,
            batteryOptimizationState = batteryOptimizationState,
            nextScheduledTransitionEpochMs = nextTransitionEpochMs,
            nextScheduledTransitionTiming = scheduledTimingMode(nextTransitionEpochMs, snapshot.exactAlarmAvailable),
            lastScheduleReconciliationEpochMs = reconciliation?.timestampEpochMs,
            lastScheduleReconciliationOutcome = reconciliation?.outcome ?: ReconciliationOutcome.NEVER_RUN,
            activeProfile = activeProfile,
            requiredEnrolledTag = requiredTag,
            enrolledTagRequired = enrolledTagRequired,
            keystoreKeyAvailable = snapshot.keystoreKeyAvailable,
            backupSchemaVersion = backupSchemaVersion,
            lastErrorCategory = lastError?.category ?: ErrorCategory.NONE,
            lastErrorEpochMs = lastError?.timestampEpochMs,
            remediationActions = remediationActions(
                nfcHardwarePresent = snapshot.nfcHardwarePresent,
                nfcHardwareEnabled = snapshot.nfcHardwareEnabled,
                accessibilityServiceEnabled = snapshot.accessibilityServiceEnabled,
                accessibilityServiceRunning = snapshot.accessibilityServiceRunning,
                notificationPermissionState = notificationPermissionState,
                exactAlarmCapability = exactAlarmCapability,
                batteryOptimizationState = batteryOptimizationState,
                requiredEnrolledTagStatus = requiredEnrolledTagStatus(enrolledTagRequired, requiredTag != null),
                nfcHmacKeyAvailable = snapshot.keystoreKeyAvailable
            )
        )
        return DiagnosticsReportFactory.create(input)
    }

    companion object {

        /**
         * The enrolled-tag id a specific active [profile] currently requires to unlock, or `null`
         * when no profile is active, the profile allows manual/any-enrolled-tag unlock, or its
         * unlock condition otherwise carries no specific tag binding. Never returns a name,
         * fingerprint, or anything beyond the plain tag id used to look the tag up.
         */
        internal fun requiredTagId(profile: Profile?): String? = when (val condition = profile?.unlockCondition) {
            is UnlockCondition.RequireNfcTag -> condition.requiredTagId
            is UnlockCondition.DurationExpiry -> condition.requiredTagId
            else -> null
        }

        /**
         * POST_NOTIFICATIONS is a moot, non-requestable concept below API 33: any [apiLevel] under
         * 33 always reports [PermissionState.NOT_REQUIRED] regardless of [granted].
         */
        internal fun notificationPermissionState(apiLevel: Int, granted: Boolean): PermissionState = when {
            apiLevel < 33 -> PermissionState.NOT_REQUIRED
            granted -> PermissionState.GRANTED
            else -> PermissionState.DENIED
        }

        /**
         * How reliably the OS can honor [nextTransitionEpochMs]: [ScheduledTimingMode.NOT_SCHEDULED]
         * when there is no upcoming transition at all, otherwise [ScheduledTimingMode.EXACT] or
         * [ScheduledTimingMode.BEST_EFFORT] depending on [exactAlarmAvailable].
         */
        internal fun scheduledTimingMode(nextTransitionEpochMs: Long?, exactAlarmAvailable: Boolean): ScheduledTimingMode =
            when {
                nextTransitionEpochMs == null -> ScheduledTimingMode.NOT_SCHEDULED
                exactAlarmAvailable -> ScheduledTimingMode.EXACT
                else -> ScheduledTimingMode.BEST_EFFORT
            }

        /**
         * The same [TagPresenceState] derivation [DiagnosticsReportFactory] applies for
         * [DiagnosticsReport.requiredEnrolledTagStatus], exposed here so [remediationActions] can
         * react to it directly rather than recomputing it from raw profile/tag records.
         */
        internal fun requiredEnrolledTagStatus(enrolledTagRequired: Boolean, requiredTagPresent: Boolean): TagPresenceState =
            when {
                !enrolledTagRequired -> TagPresenceState.NOT_REQUIRED
                requiredTagPresent -> TagPresenceState.PRESENT
                else -> TagPresenceState.MISSING
            }

        /**
         * The remediation actions the current signals call for, in a fixed, priority order --
         * NFC-related signals first since a missing tag or key blocks the app's core lock
         * enforcement, then the OS-level capability toggles. Exactly one action per unhealthy
         * signal, so with seven signals now considered here the list itself can briefly exceed
         * [DIAGNOSTICS_MAX_REMEDIATION_ACTIONS]; [DiagnosticsReportFactory] independently
         * dedupes/caps the list it is handed, dropping from the end, which is why NFC-related
         * entries are ordered first.
         *
         * A missing required enrolled tag and an unavailable NFC HMAC Keystore key share a single
         * [RemediationAction.OPEN_NFC_HUB] entry (added at most once, even when both signals are
         * unhealthy): the only local user action for either is (re-)enrolling a tag in the
         * existing NFC Hub screen, which is also what provisions the Keystore key as a side
         * effect. There is deliberately no separate "generate the key" action.
         */
        internal fun remediationActions(
            nfcHardwarePresent: Boolean,
            nfcHardwareEnabled: Boolean,
            accessibilityServiceEnabled: Boolean,
            accessibilityServiceRunning: Boolean,
            notificationPermissionState: PermissionState,
            exactAlarmCapability: PermissionState,
            batteryOptimizationState: BatteryOptimizationState,
            requiredEnrolledTagStatus: TagPresenceState = TagPresenceState.NOT_REQUIRED,
            nfcHmacKeyAvailable: Boolean = true
        ): List<RemediationAction> {
            val actions = mutableListOf<RemediationAction>()
            if (nfcHardwarePresent && !nfcHardwareEnabled) {
                actions += RemediationAction.ENABLE_NFC
            }
            if (requiredEnrolledTagStatus == TagPresenceState.MISSING || !nfcHmacKeyAvailable) {
                actions += RemediationAction.OPEN_NFC_HUB
            }
            if (!accessibilityServiceEnabled || !accessibilityServiceRunning) {
                actions += RemediationAction.OPEN_ACCESSIBILITY_SETTINGS
            }
            if (notificationPermissionState == PermissionState.DENIED) {
                actions += RemediationAction.OPEN_NOTIFICATION_SETTINGS
            }
            if (exactAlarmCapability == PermissionState.DENIED) {
                actions += RemediationAction.OPEN_EXACT_ALARM_SETTINGS
            }
            if (batteryOptimizationState == BatteryOptimizationState.RESTRICTED) {
                actions += RemediationAction.OPEN_BATTERY_OPTIMIZATION_SETTINGS
            }
            return actions
        }
    }
}
