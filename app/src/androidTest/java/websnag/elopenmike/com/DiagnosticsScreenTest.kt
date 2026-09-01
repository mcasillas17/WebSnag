package websnag.elopenmike.com

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import websnag.elopenmike.com.core.diagnostics.AccessibilityServiceState
import websnag.elopenmike.com.core.diagnostics.ActiveProfileAlias
import websnag.elopenmike.com.core.diagnostics.AppBuildInfo
import websnag.elopenmike.com.core.diagnostics.BatteryOptimizationState
import websnag.elopenmike.com.core.diagnostics.BuildTypeCategory
import websnag.elopenmike.com.core.diagnostics.DeviceInfo
import websnag.elopenmike.com.core.diagnostics.DiagnosticsReport
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.diagnostics.LastErrorInfo
import websnag.elopenmike.com.core.diagnostics.NextScheduledTransition
import websnag.elopenmike.com.core.diagnostics.NfcHardwareState
import websnag.elopenmike.com.core.diagnostics.PermissionState
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.diagnostics.RemediationAction
import websnag.elopenmike.com.core.diagnostics.ScheduleReconciliationStatus
import websnag.elopenmike.com.core.diagnostics.ScheduledTimingMode
import websnag.elopenmike.com.core.diagnostics.TagPresenceState
import websnag.elopenmike.com.ui.diagnostics.DiagnosticsScreen

/**
 * Compose UI tests for [DiagnosticsScreen] using a directly-constructed, fixed, payload-free
 * [DiagnosticsReport] fixture -- never the production [websnag.elopenmike.com.MainActivity] or a
 * real [websnag.elopenmike.com.core.diagnostics.DiagnosticsRepository] -- so this exercises only
 * the screen's own rendering and callback wiring.
 */
@Suppress("TestFunctionName")
class DiagnosticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fixtureReport = DiagnosticsReport(
        generatedAtEpochMs = 1_700_000_000_000L,
        appBuildInfo = AppBuildInfo(versionName = "9.9.9", versionCode = 123L, buildType = BuildTypeCategory.DEBUG),
        deviceInfo = DeviceInfo(apiLevel = 34, manufacturer = "Acme", model = "Widget X"),
        nfcHardwareState = NfcHardwareState(present = true, enabled = false),
        accessibilityServiceState = AccessibilityServiceState(enabled = true, running = false),
        notificationPermissionState = PermissionState.DENIED,
        exactAlarmCapability = PermissionState.GRANTED,
        batteryOptimizationState = BatteryOptimizationState.RESTRICTED,
        nextScheduledTransition = NextScheduledTransition(
            epochMs = 1_700_000_500_000L,
            timingMode = ScheduledTimingMode.BEST_EFFORT
        ),
        scheduleReconciliationStatus = ScheduleReconciliationStatus(
            lastReconciledEpochMs = 1_699_999_000_000L,
            outcome = ReconciliationOutcome.ACTIVATED
        ),
        activeProfileAlias = ActiveProfileAlias(alias = "profile-0123456789ab"),
        requiredEnrolledTagStatus = TagPresenceState.MISSING,
        keystoreKeyAvailable = false,
        backupSchemaVersion = 1,
        lastError = LastErrorInfo(category = ErrorCategory.NFC, occurredAtEpochMs = 1_699_998_000_000L),
        remediationActions = listOf(RemediationAction.OPEN_NFC_HUB, RemediationAction.OPEN_ACCESSIBILITY_SETTINGS)
    )

    /**
     * Scrolls the screen's [androidx.compose.foundation.lazy.LazyColumn] until a node matching
     * [text] is present in the semantics tree. Lazy list items outside the visible viewport are
     * never composed, so every assertion or interaction against content that may be offscreen must
     * scroll to it first rather than assume the whole report is simultaneously present.
     */
    private fun scrollToNodeWithText(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun displaysEverySectionLabelAndCriticalValueFromTheReport() {
        composeRule.setContent {
            DiagnosticsScreen(
                report = fixtureReport,
                isLoading = false,
                collectionErrorMessage = null,
                onRefresh = {},
                onExport = {},
                onRemediationAction = {}
            )
        }

        // Section labels.
        listOf(
            "App", "Device", "NFC", "Accessibility", "Permissions", "Schedule",
            "Profile & tag", "Security", "Backup", "Last error", "Recommended actions"
        ).forEach { sectionLabel ->
            scrollToNodeWithText(sectionLabel)
            composeRule.onNodeWithText(sectionLabel).assertExists()
        }

        // Critical values, each rendered as part of a unique labelled row.
        listOf(
            "Version name: 9.9.9",
            "Version code: 123",
            "Build type: DEBUG",
            "API level: 34",
            "Manufacturer: Acme",
            "Model: Widget X",
            "NFC present: Yes",
            "NFC enabled: No",
            "Accessibility enabled: Yes",
            "Accessibility running: No",
            "Schema version: 1",
            "Generated at: 2023-11-14 22:13 UTC",
            "Notification permission: DENIED",
            "Exact alarm: GRANTED",
            "Battery optimization: RESTRICTED",
            "Active profile alias: profile-0123456789ab",
            "Required tag: MISSING",
            "Keystore key available: No",
            "Backup schema version: 1",
            "Last error category: NFC"
        ).forEach { criticalValue ->
            scrollToNodeWithText(criticalValue)
            composeRule.onNodeWithText(criticalValue).assertExists()
        }
    }

    @Test
    fun tappingExportInvokesTheCallbackWithTheExactDisplayedReport() {
        var capturedReport: DiagnosticsReport? = null
        composeRule.setContent {
            DiagnosticsScreen(
                report = fixtureReport,
                isLoading = false,
                collectionErrorMessage = null,
                onRefresh = {},
                onExport = { capturedReport = it },
                onRemediationAction = {}
            )
        }

        scrollToNodeWithText("Export diagnostics (JSON)")
        composeRule.onNodeWithText("Export diagnostics (JSON)").performClick()

        assertEquals(fixtureReport, capturedReport)
    }

    @Test
    fun tappingARemediationButtonInvokesTheCallbackWithTheExactTypedAction() {
        var capturedAction: RemediationAction? = null
        composeRule.setContent {
            DiagnosticsScreen(
                report = fixtureReport,
                isLoading = false,
                collectionErrorMessage = null,
                onRefresh = {},
                onExport = {},
                onRemediationAction = { capturedAction = it }
            )
        }

        scrollToNodeWithText("Open NFC Hub")
        composeRule.onNodeWithText("Open NFC Hub").performClick()

        assertEquals(RemediationAction.OPEN_NFC_HUB, capturedAction)
    }

    @Test
    fun explicitlyShowsALoadingStateWithoutAReport() {
        composeRule.setContent {
            DiagnosticsScreen(
                report = null,
                isLoading = true,
                collectionErrorMessage = null,
                onRefresh = {},
                onExport = {},
                onRemediationAction = {}
            )
        }

        composeRule.onNodeWithText("Loading diagnostics\u2026").assertExists()
    }

    @Test
    fun explicitlyShowsACollectionErrorAndRetryInvokesRefreshWithoutFabricatingAReport() {
        var refreshed = false
        composeRule.setContent {
            DiagnosticsScreen(
                report = null,
                isLoading = false,
                collectionErrorMessage = "Diagnostics could not be collected.",
                onRefresh = { refreshed = true },
                onExport = {},
                onRemediationAction = {}
            )
        }

        composeRule.onNodeWithText("Diagnostics could not be collected.").assertExists()
        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(true, refreshed)
        // No report content should have been fabricated for the error state.
        composeRule.onNodeWithText("App").assertDoesNotExist()
    }
}
