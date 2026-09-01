package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.diagnostics.AppBuildInfo
import websnag.elopenmike.com.core.diagnostics.BatteryOptimizationState
import websnag.elopenmike.com.core.diagnostics.BuildTypeCategory
import websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH
import websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_MAX_REMEDIATION_ACTIONS
import websnag.elopenmike.com.core.diagnostics.DiagnosticsFactoryInput
import websnag.elopenmike.com.core.diagnostics.DiagnosticsJsonExporter
import websnag.elopenmike.com.core.diagnostics.DiagnosticsReport
import websnag.elopenmike.com.core.diagnostics.DiagnosticsReportFactory
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.diagnostics.PermissionState
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.diagnostics.RemediationAction
import websnag.elopenmike.com.core.diagnostics.ScheduledTimingMode
import websnag.elopenmike.com.core.diagnostics.TagPresenceState
import websnag.elopenmike.com.core.model.NfcTagRecord
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition

/**
 * Sentinel strings that must never appear in an exported diagnostics report, whichever field they
 * were fed through. Each sentinel is unique so a failing assertion pinpoints exactly which leaked.
 */
private const val SENTINEL_PROFILE_NAME = "SENTINEL-PROFILE-NAME-8f2c"
private const val SENTINEL_PROFILE_DESCRIPTION = "SENTINEL-PROFILE-DESC-91ab"
private const val SENTINEL_PROFILE_ID = "SENTINEL-PROFILE-ID-4c17"
private const val SENTINEL_BLOCKED_PACKAGE = "com.sentinel.blocked.pkg.a19f"
private const val SENTINEL_TAG_ID = "SENTINEL-TAG-ID-2d6e"
private const val SENTINEL_TAG_FINGERPRINT = "SENTINEL-TAG-FINGERPRINT-77aa"
private const val SENTINEL_TAG_LABEL = "SENTINEL-TAG-LABEL-c003"
private const val SENTINEL_TAG_DESCRIPTION = "SENTINEL-TAG-DESC-e55d"

private fun hostileProfile(): Profile = Profile(
    id = SENTINEL_PROFILE_ID,
    name = SENTINEL_PROFILE_NAME,
    description = SENTINEL_PROFILE_DESCRIPTION,
    blockedPackages = setOf(SENTINEL_BLOCKED_PACKAGE),
    unlockCondition = UnlockCondition.ManualOnly,
    isActive = true
)

private fun hostileTag(): NfcTagRecord = NfcTagRecord(
    id = SENTINEL_TAG_ID,
    uidFingerprint = SENTINEL_TAG_FINGERPRINT,
    label = SENTINEL_TAG_LABEL,
    description = SENTINEL_TAG_DESCRIPTION
)

private fun baselineInput(
    activeProfile: Profile? = null,
    requiredEnrolledTag: NfcTagRecord? = null,
    enrolledTagRequired: Boolean = false,
    remediationActions: List<RemediationAction> = emptyList(),
    manufacturer: String = "Google",
    model: String = "Pixel 8",
    appVersionName: String = "1.2.3"
): DiagnosticsFactoryInput = DiagnosticsFactoryInput(
    nowEpochMs = 1_700_000_000_000L,
    appVersionName = appVersionName,
    appVersionCode = 42L,
    buildType = BuildTypeCategory.DEBUG,
    androidApiLevel = 34,
    manufacturer = manufacturer,
    model = model,
    nfcHardwarePresent = true,
    nfcHardwareEnabled = true,
    accessibilityServiceEnabled = true,
    accessibilityServiceRunning = true,
    notificationPermissionState = PermissionState.GRANTED,
    exactAlarmCapability = PermissionState.GRANTED,
    batteryOptimizationState = BatteryOptimizationState.EXEMPTED,
    nextScheduledTransitionEpochMs = 1_700_003_600_000L,
    nextScheduledTransitionTiming = ScheduledTimingMode.EXACT,
    lastScheduleReconciliationEpochMs = 1_699_999_000_000L,
    lastScheduleReconciliationOutcome = ReconciliationOutcome.KEPT_ACTIVE,
    activeProfile = activeProfile,
    requiredEnrolledTag = requiredEnrolledTag,
    enrolledTagRequired = enrolledTagRequired,
    keystoreKeyAvailable = true,
    backupSchemaVersion = 1,
    lastErrorCategory = ErrorCategory.NONE,
    lastErrorEpochMs = null,
    remediationActions = remediationActions
)

class DiagnosticsJsonExporterTest {

    @Test
    fun exportsHostileProfileAndTagWithoutLeakingAnySentinel() {
        val report = DiagnosticsReportFactory.create(
            baselineInput(
                activeProfile = hostileProfile(),
                requiredEnrolledTag = hostileTag()
            )
        )

        val json = DiagnosticsJsonExporter.export(report)

        assertFalse(json.contains(SENTINEL_PROFILE_NAME))
        assertFalse(json.contains(SENTINEL_PROFILE_DESCRIPTION))
        assertFalse(json.contains(SENTINEL_PROFILE_ID))
        assertFalse(json.contains(SENTINEL_BLOCKED_PACKAGE))
        assertFalse(json.contains(SENTINEL_TAG_ID))
        assertFalse(json.contains(SENTINEL_TAG_FINGERPRINT))
        assertFalse(json.contains(SENTINEL_TAG_LABEL))
        assertFalse(json.contains(SENTINEL_TAG_DESCRIPTION))
    }

    @Test
    fun activeProfileIsRepresentedOnlyByDeterministicHexAlias() {
        val profile = hostileProfile()
        val report = DiagnosticsReportFactory.create(baselineInput(activeProfile = profile))

        val json = DiagnosticsJsonExporter.export(report)

        val alias = report.activeProfileAlias.alias
        assertNotNull(alias)
        assertTrue(alias!!.matches(Regex("^profile-[0-9a-f]{12}$")))
        assertNotEquals(profile.id, alias)
        assertTrue(json.contains(alias))

        // Determinism: same profile id, same alias, across independent factory invocations.
        val secondReport = DiagnosticsReportFactory.create(baselineInput(activeProfile = profile.copy()))
        assertEquals(alias, secondReport.activeProfileAlias.alias)
    }

    @Test
    fun noActiveProfileYieldsNullAlias() {
        val report = DiagnosticsReportFactory.create(baselineInput(activeProfile = null))
        assertNull(report.activeProfileAlias.alias)
    }

    @Test
    fun requiredTagStatusReflectsPresenceWithoutLeakingTagFields() {
        val presentReport = DiagnosticsReportFactory.create(
            baselineInput(requiredEnrolledTag = hostileTag(), enrolledTagRequired = true)
        )
        assertEquals(TagPresenceState.PRESENT, presentReport.requiredEnrolledTagStatus)

        val missingReport = DiagnosticsReportFactory.create(
            baselineInput(requiredEnrolledTag = null, enrolledTagRequired = true)
        )
        assertEquals(TagPresenceState.MISSING, missingReport.requiredEnrolledTagStatus)

        val notRequiredReport = DiagnosticsReportFactory.create(
            baselineInput(requiredEnrolledTag = null, enrolledTagRequired = false)
        )
        assertEquals(TagPresenceState.NOT_REQUIRED, notRequiredReport.requiredEnrolledTagStatus)
    }

    @Test
    fun hostileManufacturerAndModelStringsAreRedactedNotLeaked() {
        val hostileManufacturer = "SENTINEL-MFG-../../etc/passwd-$SENTINEL_PROFILE_ID"
        val hostileModel = "SENTINEL-MODEL-\u0007bell-$SENTINEL_TAG_ID"

        val report = DiagnosticsReportFactory.create(
            baselineInput(manufacturer = hostileManufacturer, model = hostileModel)
        )
        val json = DiagnosticsJsonExporter.export(report)

        assertFalse(json.contains(SENTINEL_PROFILE_ID))
        assertFalse(json.contains(SENTINEL_TAG_ID))
        assertFalse(report.deviceInfo.manufacturer.contains('/'))
        assertFalse(report.deviceInfo.model.any { it.isISOControl() })
    }

    @Test
    fun overlongDisplayStringsAreTruncatedToMaxLength() {
        val longModel = "M".repeat(500)
        val report = DiagnosticsReportFactory.create(baselineInput(model = longModel))
        assertEquals(DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH, report.deviceInfo.model.length)
    }

    @Test
    fun remediationActionsAreCappedAtFiveInFactoryOutput() {
        val many = listOf(
            RemediationAction.OPEN_NOTIFICATION_SETTINGS,
            RemediationAction.OPEN_ACCESSIBILITY_SETTINGS,
            RemediationAction.OPEN_BATTERY_OPTIMIZATION_SETTINGS,
            RemediationAction.OPEN_EXACT_ALARM_SETTINGS,
            RemediationAction.ENABLE_NFC,
            RemediationAction.ENROLL_REQUIRED_TAG,
            RemediationAction.RETRY_KEYSTORE_KEY_GENERATION
        )
        val report = DiagnosticsReportFactory.create(baselineInput(remediationActions = many))
        assertTrue(report.remediationActions.size <= DIAGNOSTICS_MAX_REMEDIATION_ACTIONS)
    }

    @Test
    fun exportedJsonHasFixedTopLevelFieldCountRegardlessOfInputVariation() {
        val minimalJson = DiagnosticsJsonExporter.export(
            DiagnosticsReportFactory.create(baselineInput())
        )
        val fullJson = DiagnosticsJsonExporter.export(
            DiagnosticsReportFactory.create(
                baselineInput(
                    activeProfile = hostileProfile(),
                    requiredEnrolledTag = hostileTag(),
                    remediationActions = listOf(RemediationAction.ENABLE_NFC)
                )
            )
        )

        val minimalKeys = kotlinx.serialization.json.Json.parseToJsonElement(minimalJson)
            .let { it as kotlinx.serialization.json.JsonObject }.keys
        val fullKeys = kotlinx.serialization.json.Json.parseToJsonElement(fullJson)
            .let { it as kotlinx.serialization.json.JsonObject }.keys

        assertEquals(DiagnosticsReport.FIXED_FIELD_COUNT, minimalKeys.size)
        assertEquals(minimalKeys, fullKeys)
    }

    @Test
    fun exportIncludesExplicitSchemaVersionAndGeneratedTimestamp() {
        val report = DiagnosticsReportFactory.create(baselineInput())
        val json = DiagnosticsJsonExporter.export(report)

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"generatedAtEpochMs\":1700000000000"))
    }

    @Test
    fun exportOfWellFormedReportStaysUnderTheSizeBound() {
        val report = DiagnosticsReportFactory.create(
            baselineInput(
                activeProfile = hostileProfile(),
                requiredEnrolledTag = hostileTag(),
                remediationActions = listOf(RemediationAction.ENABLE_NFC)
            )
        )
        val json = DiagnosticsJsonExporter.export(report)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_MAX_EXPORT_BYTES)
    }

    @Test
    fun exportThrowsTypedExceptionRatherThanTruncatingWhenOverSizeBound() {
        // Bypass the factory (whose sanitization would normally cap this) to prove the exporter
        // itself enforces the hard byte bound rather than relying solely on upstream sanitization.
        val oversizedReport = DiagnosticsReportFactory.create(baselineInput()).copy(
            appBuildInfo = AppBuildInfo(
                versionName = "X".repeat(websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_MAX_EXPORT_BYTES),
                versionCode = 1L,
                buildType = BuildTypeCategory.DEBUG
            )
        )

        val exception = assertThrows(websnag.elopenmike.com.core.diagnostics.DiagnosticsExportException.ExportTooLarge::class.java) {
            DiagnosticsJsonExporter.export(oversizedReport)
        }

        assertTrue(exception.actualBytes > exception.maxBytes)
        assertEquals(websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_MAX_EXPORT_BYTES, exception.maxBytes)
    }

    @Test
    fun exportRejectsDirectlyConstructedReportWithControlCharacterInManufacturer() {
        // Bypass the factory's sanitizeDisplayString entirely: DeviceInfo is constructed directly
        // so the exporter itself is the only remaining guard against an unsafe system string.
        val hostileReport = DiagnosticsReportFactory.create(baselineInput()).copy(
            deviceInfo = websnag.elopenmike.com.core.diagnostics.DeviceInfo(
                apiLevel = 34,
                manufacturer = "Acme\u0007Corp",
                model = "Widget"
            )
        )

        assertThrows(
            websnag.elopenmike.com.core.diagnostics.DiagnosticsExportException.UnsafeDisplayString::class.java
        ) {
            DiagnosticsJsonExporter.export(hostileReport)
        }
    }

    @Test
    fun exportRejectsDirectlyConstructedReportWithPathLikeModel() {
        val hostileReport = DiagnosticsReportFactory.create(baselineInput()).copy(
            deviceInfo = websnag.elopenmike.com.core.diagnostics.DeviceInfo(
                apiLevel = 34,
                manufacturer = "Acme",
                model = "../../etc/passwd"
            )
        )

        assertThrows(
            websnag.elopenmike.com.core.diagnostics.DiagnosticsExportException.UnsafeDisplayString::class.java
        ) {
            DiagnosticsJsonExporter.export(hostileReport)
        }
    }

    @Test
    fun exportRejectsDirectlyConstructedReportWithBackslashPathLikeManufacturer() {
        val hostileReport = DiagnosticsReportFactory.create(baselineInput()).copy(
            deviceInfo = websnag.elopenmike.com.core.diagnostics.DeviceInfo(
                apiLevel = 34,
                manufacturer = "C:\\Windows\\System32",
                model = "Widget"
            )
        )

        assertThrows(
            websnag.elopenmike.com.core.diagnostics.DiagnosticsExportException.UnsafeDisplayString::class.java
        ) {
            DiagnosticsJsonExporter.export(hostileReport)
        }
    }

    @Test
    fun exportRejectsDirectlyConstructedReportWithOverlongButOtherwiseSafeVersionName() {
        // Safe characters only (no control chars, no path separators) and well under the total
        // 16,384-byte export bound, so only the per-field 80-char display-string limit is at
        // stake here -- proving that check is independent of the overall byte-size guard.
        val overlongSafeVersionName = "V".repeat(DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH + 1)
        val hostileReport = DiagnosticsReportFactory.create(baselineInput()).copy(
            appBuildInfo = AppBuildInfo(
                versionName = overlongSafeVersionName,
                versionCode = 1L,
                buildType = BuildTypeCategory.DEBUG
            )
        )

        val exception = assertThrows(
            websnag.elopenmike.com.core.diagnostics.DiagnosticsExportException.UnsafeDisplayString::class.java
        ) {
            DiagnosticsJsonExporter.export(hostileReport)
        }

        assertTrue(exception.message!!.contains("versionName"))
    }

    @Test
    fun exportOfCompliantDirectlyConstructedFieldsAtExactlyTheLengthLimitStillSucceeds() {
        // Exactly at the boundary (not over it): must NOT throw, proving the check is a strict
        // greater-than comparison against DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH.
        val exactLengthModel = "M".repeat(DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH)
        val compliantReport = DiagnosticsReportFactory.create(baselineInput()).copy(
            deviceInfo = websnag.elopenmike.com.core.diagnostics.DeviceInfo(
                apiLevel = 34,
                manufacturer = "Acme",
                model = exactLengthModel
            )
        )

        val json = DiagnosticsJsonExporter.export(compliantReport)
        assertTrue(json.contains(exactLengthModel))
    }
}
