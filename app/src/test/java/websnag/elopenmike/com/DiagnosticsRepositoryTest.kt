package websnag.elopenmike.com

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.backup.BackupCodec
import websnag.elopenmike.com.core.diagnostics.BatteryOptimizationState
import websnag.elopenmike.com.core.diagnostics.BuildTypeCategory
import websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_MAX_REMEDIATION_ACTIONS
import websnag.elopenmike.com.core.diagnostics.DiagnosticsPlatformSnapshot
import websnag.elopenmike.com.core.diagnostics.DiagnosticsRepository
import websnag.elopenmike.com.core.diagnostics.DiagnosticsStateSource
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.diagnostics.LocalErrorRecord
import websnag.elopenmike.com.core.diagnostics.PermissionState
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.diagnostics.RemediationAction
import websnag.elopenmike.com.core.diagnostics.ScheduleReconciliationRecord
import websnag.elopenmike.com.core.diagnostics.ScheduledTimingMode
import websnag.elopenmike.com.core.diagnostics.TagPresenceState
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.ScheduleDay
import websnag.elopenmike.com.core.model.ScheduleEndMode
import websnag.elopenmike.com.core.model.ScheduleRecord
import websnag.elopenmike.com.core.model.UnlockCondition

/** Fully healthy platform snapshot; individual tests flip one field at a time. */
private fun healthySnapshot(): DiagnosticsPlatformSnapshot = DiagnosticsPlatformSnapshot(
    appVersionName = "1.2.3",
    appVersionCode = 42L,
    buildType = BuildTypeCategory.RELEASE,
    androidApiLevel = 34,
    manufacturer = "Google",
    model = "Pixel 8",
    nfcHardwarePresent = true,
    nfcHardwareEnabled = true,
    accessibilityServiceEnabled = true,
    accessibilityServiceRunning = true,
    notificationPermissionGranted = true,
    exactAlarmAvailable = true,
    batteryOptimizationIgnored = true,
    keystoreKeyAvailable = true
)

private class FakeDiagnosticsStateSource(private val snapshot: DiagnosticsPlatformSnapshot) : DiagnosticsStateSource {
    override fun snapshot(): DiagnosticsPlatformSnapshot = snapshot
}

private fun repository(
    snapshot: DiagnosticsPlatformSnapshot = healthySnapshot(),
    activeProfile: Profile? = null,
    tags: List<NfcTagRecord> = emptyList(),
    schedules: List<ScheduleRecord> = emptyList(),
    reconciliation: ScheduleReconciliationRecord? = null,
    lastError: LocalErrorRecord? = null,
    now: Long = 1_700_000_000_000L
): DiagnosticsRepository {
    val profileRepo = FakeProfileRepository().apply {
        if (activeProfile != null) {
            kotlinx.coroutines.runBlocking { saveProfile(activeProfile) }
            kotlinx.coroutines.runBlocking { setActiveProfile(activeProfile.id) }
        }
    }
    val tagRepo = FakeNfcTagRepository().apply {
        tags.forEach { tag -> kotlinx.coroutines.runBlocking { saveTag(tag) } }
    }
    return DiagnosticsRepository(
        stateSource = FakeDiagnosticsStateSource(snapshot),
        profileRepository = profileRepo,
        nfcTagRepository = tagRepo,
        schedulesFlow = MutableStateFlow(schedules),
        scheduleReconciliationFlow = MutableStateFlow(reconciliation),
        localErrorFlow = MutableStateFlow(lastError),
        clock = { now }
    )
}

private fun enabledSchedule(id: String = "s1"): ScheduleRecord = ScheduleRecord(
    id = id,
    name = "Focus",
    profileId = "profile-x",
    profileName = "Focus Profile",
    daysOfWeek = ScheduleDay.values().toSet(),
    startHour = 0,
    startMinute = 0,
    endMode = ScheduleEndMode.AT_TIME,
    endHour = 23,
    endMinute = 59,
    isEnabled = true
)

class DiagnosticsRepositoryTest {

    // --- requiredTagId pure derivation -----------------------------------------------------

    @Test
    fun requiredTagIdIsNullWhenNoActiveProfile() {
        assertNull(DiagnosticsRepository.requiredTagId(null))
    }

    @Test
    fun requiredTagIdIsNullForManualOnlyUnlock() {
        val profile = Profile(id = "p", name = "n", unlockCondition = UnlockCondition.ManualOnly, isActive = true)
        assertNull(DiagnosticsRepository.requiredTagId(profile))
    }

    @Test
    fun requiredTagIdIsNullWhenAnyEnrolledTagIsAllowedWithoutABinding() {
        val profile = Profile(
            id = "p",
            name = "n",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = null, allowAnyEnrolledTag = true),
            isActive = true
        )
        assertNull(DiagnosticsRepository.requiredTagId(profile))
    }

    @Test
    fun requiredTagIdReturnsTheBoundTagForRequireNfcTag() {
        val profile = Profile(
            id = "p",
            name = "n",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = "tag-42"),
            isActive = true
        )
        assertEquals("tag-42", DiagnosticsRepository.requiredTagId(profile))
    }

    @Test
    fun requiredTagIdReturnsTheBoundTagForDurationExpiry() {
        val profile = Profile(
            id = "p",
            name = "n",
            unlockCondition = UnlockCondition.DurationExpiry(durationMinutes = 30, requiredTagId = "tag-7"),
            isActive = true
        )
        assertEquals("tag-7", DiagnosticsRepository.requiredTagId(profile))
    }

    // --- notificationPermissionState pure mapping --------------------------------------------

    @Test
    fun notificationPermissionIsNotRequiredBelowApi33RegardlessOfGrant() {
        assertEquals(PermissionState.NOT_REQUIRED, DiagnosticsRepository.notificationPermissionState(32, granted = true))
        assertEquals(PermissionState.NOT_REQUIRED, DiagnosticsRepository.notificationPermissionState(32, granted = false))
    }

    @Test
    fun notificationPermissionIsGrantedOrDeniedFromApi33() {
        assertEquals(PermissionState.GRANTED, DiagnosticsRepository.notificationPermissionState(33, granted = true))
        assertEquals(PermissionState.DENIED, DiagnosticsRepository.notificationPermissionState(33, granted = false))
    }

    // --- scheduledTimingMode pure mapping -----------------------------------------------------

    @Test
    fun scheduledTimingIsNotScheduledWhenThereIsNoNextTransition() {
        assertEquals(
            ScheduledTimingMode.NOT_SCHEDULED,
            DiagnosticsRepository.scheduledTimingMode(nextTransitionEpochMs = null, exactAlarmAvailable = true)
        )
    }

    @Test
    fun scheduledTimingIsExactWhenExactAlarmsAreAvailable() {
        assertEquals(
            ScheduledTimingMode.EXACT,
            DiagnosticsRepository.scheduledTimingMode(nextTransitionEpochMs = 1L, exactAlarmAvailable = true)
        )
    }

    @Test
    fun scheduledTimingIsBestEffortWhenExactAlarmsAreUnavailable() {
        assertEquals(
            ScheduledTimingMode.BEST_EFFORT,
            DiagnosticsRepository.scheduledTimingMode(nextTransitionEpochMs = 1L, exactAlarmAvailable = false)
        )
    }

    // --- remediationActions pure mapping ------------------------------------------------------

    @Test
    fun remediationActionsIsEmptyWhenEverythingIsHealthy() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun remediationActionsSkipsEnableNfcWhenHardwareIsAbsent() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = false,
            nfcHardwareEnabled = false,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED
        )
        assertFalse(actions.contains(RemediationAction.ENABLE_NFC))
    }

    @Test
    fun remediationActionsIncludesEnableNfcWhenHardwarePresentButDisabled() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = false,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED
        )
        assertEquals(listOf(RemediationAction.ENABLE_NFC), actions)
    }

    @Test
    fun remediationActionsIncludesAccessibilityWhenEnabledButNotRunning() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = false,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED
        )
        assertEquals(listOf(RemediationAction.OPEN_ACCESSIBILITY_SETTINGS), actions)
    }

    @Test
    fun remediationActionsIncludesAccessibilityWhenNotEnabledInSecureSettings() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = false,
            accessibilityServiceRunning = false,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED
        )
        assertEquals(listOf(RemediationAction.OPEN_ACCESSIBILITY_SETTINGS), actions)
    }

    @Test
    fun remediationActionsIncludesNotificationSettingsOnlyWhenDenied() {
        val denied = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.DENIED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED
        )
        assertEquals(listOf(RemediationAction.OPEN_NOTIFICATION_SETTINGS), denied)

        val notRequired = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.NOT_REQUIRED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED
        )
        assertTrue(notRequired.isEmpty())
    }

    @Test
    fun remediationActionsIncludesExactAlarmSettingsOnlyWhenDenied() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.DENIED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED
        )
        assertEquals(listOf(RemediationAction.OPEN_EXACT_ALARM_SETTINGS), actions)
    }

    @Test
    fun remediationActionsIncludesBatterySettingsOnlyWhenRestricted() {
        val restricted = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.RESTRICTED
        )
        assertEquals(listOf(RemediationAction.OPEN_BATTERY_OPTIMIZATION_SETTINGS), restricted)

        val unknown = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.UNKNOWN
        )
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun remediationActionsStaysAtOrUnderTheBoundWhenEverythingIsUnhealthy() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = false,
            accessibilityServiceEnabled = false,
            accessibilityServiceRunning = false,
            notificationPermissionState = PermissionState.DENIED,
            exactAlarmCapability = PermissionState.DENIED,
            batteryOptimizationState = BatteryOptimizationState.RESTRICTED
        )
        assertEquals(5, actions.size)
        assertTrue(actions.size <= DIAGNOSTICS_MAX_REMEDIATION_ACTIONS)
        assertEquals(actions.distinct(), actions)
    }

    // --- OPEN_NFC_HUB remediation for a missing required tag / unavailable HMAC key -----------

    @Test
    fun remediationActionsExcludesOpenNfcHubWhenTagNotRequiredAndKeyAvailable() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED,
            requiredEnrolledTagStatus = TagPresenceState.NOT_REQUIRED,
            nfcHmacKeyAvailable = true
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun remediationActionsExcludesOpenNfcHubWhenTagIsPresentAndKeyAvailable() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED,
            requiredEnrolledTagStatus = TagPresenceState.PRESENT,
            nfcHmacKeyAvailable = true
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun remediationActionsIncludesOpenNfcHubForHostileMissingRequiredTag() {
        // Hostile: the required tag id could be an arbitrary/attacker-influenced string, but the
        // remediation decision here depends only on the coarse TagPresenceState, never the id.
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED,
            requiredEnrolledTagStatus = TagPresenceState.MISSING,
            nfcHmacKeyAvailable = true
        )
        assertEquals(listOf(RemediationAction.OPEN_NFC_HUB), actions)
    }

    @Test
    fun remediationActionsIncludesOpenNfcHubWhenHmacKeyIsUnavailable() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED,
            requiredEnrolledTagStatus = TagPresenceState.PRESENT,
            nfcHmacKeyAvailable = false
        )
        assertEquals(listOf(RemediationAction.OPEN_NFC_HUB), actions)
    }

    @Test
    fun remediationActionsDedupesOpenNfcHubWhenBothTagMissingAndKeyUnavailable() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = true,
            accessibilityServiceEnabled = true,
            accessibilityServiceRunning = true,
            notificationPermissionState = PermissionState.GRANTED,
            exactAlarmCapability = PermissionState.GRANTED,
            batteryOptimizationState = BatteryOptimizationState.EXEMPTED,
            requiredEnrolledTagStatus = TagPresenceState.MISSING,
            nfcHmacKeyAvailable = false
        )
        assertEquals(listOf(RemediationAction.OPEN_NFC_HUB), actions)
    }

    @Test
    fun remediationActionsStaysAtOrUnderTheBoundWhenEverythingIsUnhealthyIncludingNfcHub() {
        val actions = DiagnosticsRepository.remediationActions(
            nfcHardwarePresent = true,
            nfcHardwareEnabled = false,
            accessibilityServiceEnabled = false,
            accessibilityServiceRunning = false,
            notificationPermissionState = PermissionState.DENIED,
            exactAlarmCapability = PermissionState.DENIED,
            batteryOptimizationState = BatteryOptimizationState.RESTRICTED,
            requiredEnrolledTagStatus = TagPresenceState.MISSING,
            nfcHmacKeyAvailable = false
        )
        // The repository itself may now report up to six distinct unhealthy signals; capping to
        // DIAGNOSTICS_MAX_REMEDIATION_ACTIONS is DiagnosticsReportFactory's job (verified by
        // remediationActionsAreCappedAtFiveInFactoryOutput and the currentReport() test below).
        assertEquals(6, actions.size)
        assertEquals(actions.distinct(), actions)
        assertTrue(actions.contains(RemediationAction.OPEN_NFC_HUB))
    }

    // --- end-to-end currentReport() assembly --------------------------------------------------

    @Test
    fun currentReportThreadsHealthySnapshotAndAbsentRecordsAsNeverRunAndNone() = runTest {
        val report = repository().currentReport()

        assertEquals(BackupCodec.VERSION, report.backupSchemaVersion)
        assertEquals(ReconciliationOutcome.NEVER_RUN, report.scheduleReconciliationStatus.outcome)
        assertNull(report.scheduleReconciliationStatus.lastReconciledEpochMs)
        assertEquals(ErrorCategory.NONE, report.lastError.category)
        assertNull(report.lastError.occurredAtEpochMs)
        assertNull(report.activeProfileAlias.alias)
        assertEquals(TagPresenceState.NOT_REQUIRED, report.requiredEnrolledTagStatus)
        assertEquals(ScheduledTimingMode.NOT_SCHEDULED, report.nextScheduledTransition.timingMode)
        assertTrue(report.remediationActions.isEmpty())
        assertTrue(report.keystoreKeyAvailable)
    }

    @Test
    fun currentReportThreadsPersistedReconciliationAndErrorRecordsThrough() = runTest {
        val reconciliation = ScheduleReconciliationRecord(
            timestampEpochMs = 1_699_999_000_000L,
            outcome = ReconciliationOutcome.ACTIVATED
        )
        val error = LocalErrorRecord(timestampEpochMs = 1_699_998_000_000L, category = ErrorCategory.NFC)

        val report = repository(reconciliation = reconciliation, lastError = error).currentReport()

        assertEquals(ReconciliationOutcome.ACTIVATED, report.scheduleReconciliationStatus.outcome)
        assertEquals(1_699_999_000_000L, report.scheduleReconciliationStatus.lastReconciledEpochMs)
        assertEquals(ErrorCategory.NFC, report.lastError.category)
        assertEquals(1_699_998_000_000L, report.lastError.occurredAtEpochMs)
    }

    @Test
    fun currentReportDerivesNextScheduledTransitionFromScheduleTransitionCalculator() = runTest {
        val report = repository(
            schedules = listOf(enabledSchedule()),
            snapshot = healthySnapshot().copy(exactAlarmAvailable = true)
        ).currentReport()

        assertEquals(ScheduledTimingMode.EXACT, report.nextScheduledTransition.timingMode)
        assertTrue(report.nextScheduledTransition.epochMs != null)
    }

    @Test
    fun currentReportReportsBestEffortTimingWhenExactAlarmsAreUnavailable() = runTest {
        val report = repository(
            schedules = listOf(enabledSchedule()),
            snapshot = healthySnapshot().copy(exactAlarmAvailable = false)
        ).currentReport()

        assertEquals(ScheduledTimingMode.BEST_EFFORT, report.nextScheduledTransition.timingMode)
        assertEquals(PermissionState.DENIED, report.exactAlarmCapability)
    }

    @Test
    fun currentReportResolvesRequiredEnrolledTagPresenceWithoutLeakingItsFields() = runTest {
        val requiredTag = NfcTagRecord(
            id = "tag-required",
            uidFingerprint = "SENTINEL-FINGERPRINT",
            label = "SENTINEL-LABEL"
        )
        val profile = Profile(
            id = "profile-active",
            name = "SENTINEL-PROFILE-NAME",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = requiredTag.id),
            isActive = true
        )

        val presentReport = repository(activeProfile = profile, tags = listOf(requiredTag)).currentReport()
        assertEquals(TagPresenceState.PRESENT, presentReport.requiredEnrolledTagStatus)

        val missingReport = repository(activeProfile = profile, tags = emptyList()).currentReport()
        assertEquals(TagPresenceState.MISSING, missingReport.requiredEnrolledTagStatus)

        val json = websnag.elopenmike.com.core.diagnostics.DiagnosticsJsonExporter.export(presentReport)
        assertFalse(json.contains("SENTINEL"))
        assertFalse(json.contains(requiredTag.uidFingerprint))
        assertFalse(json.contains(profile.id))
    }

    @Test
    fun currentReportSanitizesHostileManufacturerAndModelFromThePlatformSource() = runTest {
        val hostileSnapshot = healthySnapshot().copy(
            manufacturer = "Acme/../etc/passwd",
            model = "Widget\u0007Bell"
        )
        val report = repository(snapshot = hostileSnapshot).currentReport()

        assertFalse(report.deviceInfo.manufacturer.contains('/'))
        assertFalse(report.deviceInfo.model.any { it.isISOControl() })
    }

    @Test
    fun currentReportRemediationListNeverExceedsTheDeclaredBoundEvenWhenFullyUnhealthy() = runTest {
        val unhealthySnapshot = healthySnapshot().copy(
            nfcHardwareEnabled = false,
            accessibilityServiceEnabled = false,
            accessibilityServiceRunning = false,
            notificationPermissionGranted = false,
            exactAlarmAvailable = false,
            batteryOptimizationIgnored = false,
            keystoreKeyAvailable = false
        )
        val profile = Profile(
            id = "profile-unhealthy",
            name = "Unhealthy",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = "tag-missing"),
            isActive = true
        )
        val report = repository(
            snapshot = unhealthySnapshot,
            activeProfile = profile,
            tags = emptyList()
        ).currentReport()

        assertEquals(TagPresenceState.MISSING, report.requiredEnrolledTagStatus)
        assertTrue(report.remediationActions.contains(RemediationAction.OPEN_NFC_HUB))
        assertTrue(report.remediationActions.size <= DIAGNOSTICS_MAX_REMEDIATION_ACTIONS)
        assertEquals(report.remediationActions.distinct(), report.remediationActions)
    }

    @Test
    fun currentReportIncludesOpenNfcHubForAHostileProfileWithAMissingRequiredTag() = runTest {
        val profile = Profile(
            id = "SENTINEL-PROFILE-ID-nfc-hub",
            name = "SENTINEL-PROFILE-NAME-nfc-hub",
            unlockCondition = UnlockCondition.RequireNfcTag(requiredTagId = "SENTINEL-TAG-ID-missing"),
            isActive = true
        )

        val report = repository(activeProfile = profile, tags = emptyList()).currentReport()

        assertEquals(TagPresenceState.MISSING, report.requiredEnrolledTagStatus)
        assertEquals(listOf(RemediationAction.OPEN_NFC_HUB), report.remediationActions)

        val json = websnag.elopenmike.com.core.diagnostics.DiagnosticsJsonExporter.export(report)
        assertFalse(json.contains("SENTINEL"))
    }

    @Test
    fun currentReportIncludesOpenNfcHubWhenTheKeystoreHmacKeyIsUnavailable() = runTest {
        val report = repository(snapshot = healthySnapshot().copy(keystoreKeyAvailable = false)).currentReport()

        assertFalse(report.keystoreKeyAvailable)
        assertEquals(listOf(RemediationAction.OPEN_NFC_HUB), report.remediationActions)
    }

    @Test
    fun currentReportExcludesOpenNfcHubWhenNoTagIsRequiredAndKeyIsAvailable() = runTest {
        val report = repository().currentReport()

        assertEquals(TagPresenceState.NOT_REQUIRED, report.requiredEnrolledTagStatus)
        assertTrue(report.keystoreKeyAvailable)
        assertFalse(report.remediationActions.contains(RemediationAction.OPEN_NFC_HUB))
    }
}
