package websnag.elopenmike.com

import org.junit.Assert.assertThrows
import org.junit.Test
import websnag.elopenmike.com.core.diagnostics.AccessibilityServiceState
import websnag.elopenmike.com.core.diagnostics.ActiveProfileAlias
import websnag.elopenmike.com.core.diagnostics.AppBuildInfo
import websnag.elopenmike.com.core.diagnostics.BatteryOptimizationState
import websnag.elopenmike.com.core.diagnostics.BuildTypeCategory
import websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_SCHEMA_VERSION
import websnag.elopenmike.com.core.diagnostics.DeviceInfo
import websnag.elopenmike.com.core.diagnostics.DiagnosticsReport
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.diagnostics.LastErrorInfo
import websnag.elopenmike.com.core.diagnostics.NextScheduledTransition
import websnag.elopenmike.com.core.diagnostics.NfcHardwareState
import websnag.elopenmike.com.core.diagnostics.PermissionState
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.diagnostics.ScheduleReconciliationStatus
import websnag.elopenmike.com.core.diagnostics.ScheduledTimingMode
import websnag.elopenmike.com.core.diagnostics.TagPresenceState

/**
 * Direct-construction invariant tests for [DiagnosticsReport]. These bypass
 * [websnag.elopenmike.com.core.diagnostics.DiagnosticsReportFactory] on purpose to prove the model
 * itself refuses to represent an out-of-band schema version, regardless of construction path.
 */
class DiagnosticsModelsTest {

    private fun validReportBuilder(schemaVersion: Int): DiagnosticsReport = DiagnosticsReport(
        schemaVersion = schemaVersion,
        generatedAtEpochMs = 1L,
        appBuildInfo = AppBuildInfo(
            versionName = "1.0.0",
            versionCode = 1L,
            buildType = BuildTypeCategory.DEBUG
        ),
        deviceInfo = DeviceInfo(apiLevel = 30, manufacturer = "Acme", model = "Widget"),
        nfcHardwareState = NfcHardwareState(present = false, enabled = false),
        accessibilityServiceState = AccessibilityServiceState(enabled = false, running = false),
        notificationPermissionState = PermissionState.NOT_REQUIRED,
        exactAlarmCapability = PermissionState.NOT_REQUIRED,
        batteryOptimizationState = BatteryOptimizationState.UNKNOWN,
        nextScheduledTransition = NextScheduledTransition(epochMs = null, timingMode = ScheduledTimingMode.NOT_SCHEDULED),
        scheduleReconciliationStatus = ScheduleReconciliationStatus(
            lastReconciledEpochMs = null,
            outcome = ReconciliationOutcome.NEVER_RUN
        ),
        activeProfileAlias = ActiveProfileAlias(alias = null),
        requiredEnrolledTagStatus = TagPresenceState.NOT_REQUIRED,
        keystoreKeyAvailable = false,
        backupSchemaVersion = 1,
        lastError = LastErrorInfo(category = ErrorCategory.NONE, occurredAtEpochMs = null),
        remediationActions = emptyList()
    )

    @Test
    fun acceptsTheCurrentSchemaVersion() {
        val report = validReportBuilder(DIAGNOSTICS_SCHEMA_VERSION)
        org.junit.Assert.assertEquals(DIAGNOSTICS_SCHEMA_VERSION, report.schemaVersion)
    }

    @Test
    fun rejectsAnySchemaVersionOtherThanTheCurrentConstant() {
        assertThrows(IllegalArgumentException::class.java) {
            validReportBuilder(DIAGNOSTICS_SCHEMA_VERSION + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validReportBuilder(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validReportBuilder(-1)
        }
    }
}
