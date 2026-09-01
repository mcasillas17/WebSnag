package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.diagnostics.BatteryOptimizationState
import websnag.elopenmike.com.core.diagnostics.BuildTypeCategory
import websnag.elopenmike.com.core.diagnostics.DiagnosticsFactoryInput
import websnag.elopenmike.com.core.diagnostics.DiagnosticsReportFactory
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.diagnostics.PermissionState
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.diagnostics.ScheduledTimingMode
import websnag.elopenmike.com.core.diagnostics.TagPresenceState
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition

/**
 * Focused tests for [DiagnosticsReportFactory]'s field-by-field mapping, independent of JSON
 * export. Sentinel/hostile-value leakage proofs live in DiagnosticsJsonExporterTest.
 */
class DiagnosticsReportFactoryTest {

    private fun input(): DiagnosticsFactoryInput = DiagnosticsFactoryInput(
        nowEpochMs = 5_000L,
        appVersionName = "9.9.9",
        appVersionCode = 99L,
        buildType = BuildTypeCategory.RELEASE,
        androidApiLevel = 33,
        manufacturer = "Samsung",
        model = "Galaxy S23",
        nfcHardwarePresent = false,
        nfcHardwareEnabled = false,
        accessibilityServiceEnabled = false,
        accessibilityServiceRunning = false,
        notificationPermissionState = PermissionState.DENIED,
        exactAlarmCapability = PermissionState.NOT_REQUIRED,
        batteryOptimizationState = BatteryOptimizationState.RESTRICTED,
        nextScheduledTransitionEpochMs = null,
        nextScheduledTransitionTiming = ScheduledTimingMode.NOT_SCHEDULED,
        lastScheduleReconciliationEpochMs = null,
        lastScheduleReconciliationOutcome = ReconciliationOutcome.NEVER_RUN,
        activeProfile = null,
        requiredEnrolledTag = null,
        enrolledTagRequired = false,
        keystoreKeyAvailable = false,
        backupSchemaVersion = 3,
        lastErrorCategory = ErrorCategory.STORAGE,
        lastErrorEpochMs = 4_500L,
        remediationActions = emptyList()
    )

    @Test
    fun mapsEveryScalarAndEnumFieldThroughUnchanged() {
        val report = DiagnosticsReportFactory.create(input())

        assertEquals(1, report.schemaVersion)
        assertEquals(5_000L, report.generatedAtEpochMs)
        assertEquals(99L, report.appBuildInfo.versionCode)
        assertEquals(BuildTypeCategory.RELEASE, report.appBuildInfo.buildType)
        assertEquals(33, report.deviceInfo.apiLevel)
        assertEquals(false, report.nfcHardwareState.present)
        assertEquals(false, report.nfcHardwareState.enabled)
        assertEquals(false, report.accessibilityServiceState.enabled)
        assertEquals(false, report.accessibilityServiceState.running)
        assertEquals(PermissionState.DENIED, report.notificationPermissionState)
        assertEquals(PermissionState.NOT_REQUIRED, report.exactAlarmCapability)
        assertEquals(BatteryOptimizationState.RESTRICTED, report.batteryOptimizationState)
        assertNull(report.nextScheduledTransition.epochMs)
        assertEquals(ScheduledTimingMode.NOT_SCHEDULED, report.nextScheduledTransition.timingMode)
        assertNull(report.scheduleReconciliationStatus.lastReconciledEpochMs)
        assertEquals(ReconciliationOutcome.NEVER_RUN, report.scheduleReconciliationStatus.outcome)
        assertEquals(TagPresenceState.NOT_REQUIRED, report.requiredEnrolledTagStatus)
        assertEquals(false, report.keystoreKeyAvailable)
        assertEquals(3, report.backupSchemaVersion)
        assertEquals(ErrorCategory.STORAGE, report.lastError.category)
        assertEquals(4_500L, report.lastError.occurredAtEpochMs)
        assertTrue(report.remediationActions.isEmpty())
    }

    @Test
    fun deriveProfileAliasIsDeterministicAndDistinctPerId() {
        val aliasA1 = DiagnosticsReportFactory.deriveProfileAlias("profile-a")
        val aliasA2 = DiagnosticsReportFactory.deriveProfileAlias("profile-a")
        val aliasB = DiagnosticsReportFactory.deriveProfileAlias("profile-b")

        assertEquals(aliasA1, aliasA2)
        assertTrue(aliasA1 != aliasB)
        assertTrue(aliasA1.matches(Regex("^profile-[0-9a-f]{12}$")))
    }

    @Test
    fun keystoreKeyAvailableIsPassedThroughAsPlainBoolean() {
        val available = DiagnosticsReportFactory.create(input().copy(keystoreKeyAvailable = true))
        val unavailable = DiagnosticsReportFactory.create(input().copy(keystoreKeyAvailable = false))

        assertTrue(available.keystoreKeyAvailable)
        assertEquals(false, unavailable.keystoreKeyAvailable)
    }

    @Test
    fun activeProfileWithBlankIdStillProducesAnAlias() {
        val profile = Profile(id = "x", name = "n", unlockCondition = UnlockCondition.ManualOnly, isActive = true)
        val report = DiagnosticsReportFactory.create(input().copy(activeProfile = profile))

        assertEquals(DiagnosticsReportFactory.deriveProfileAlias("x"), report.activeProfileAlias.alias)
    }

    @Test
    fun enrolledTagRequiredFalseAlwaysYieldsNotRequiredEvenWhenATagIsSupplied() {
        val suppliedTag = NfcTagRecord(
            id = "tag-id",
            uidFingerprint = "fingerprint",
            label = "label",
            description = "description"
        )

        val report = DiagnosticsReportFactory.create(
            input().copy(requiredEnrolledTag = suppliedTag, enrolledTagRequired = false)
        )

        assertEquals(TagPresenceState.NOT_REQUIRED, report.requiredEnrolledTagStatus)
    }
}
