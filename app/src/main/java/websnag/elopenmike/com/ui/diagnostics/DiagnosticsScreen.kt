package websnag.elopenmike.com.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import websnag.elopenmike.com.core.diagnostics.DiagnosticsReport
import websnag.elopenmike.com.core.diagnostics.RemediationAction

/** Screen title, also used as the navigation destination's top bar title. */
const val DIAGNOSTICS_SCREEN_TITLE: String = "Local diagnostics"

const val DIAGNOSTICS_SECTION_APP: String = "App"
const val DIAGNOSTICS_SECTION_DEVICE: String = "Device"
const val DIAGNOSTICS_SECTION_NFC: String = "NFC"
const val DIAGNOSTICS_SECTION_ACCESSIBILITY: String = "Accessibility"
const val DIAGNOSTICS_SECTION_PERMISSIONS: String = "Permissions"
const val DIAGNOSTICS_SECTION_SCHEDULE: String = "Schedule"
const val DIAGNOSTICS_SECTION_PROFILE_AND_TAG: String = "Profile & tag"
const val DIAGNOSTICS_SECTION_SECURITY: String = "Security"
const val DIAGNOSTICS_SECTION_BACKUP: String = "Backup"
const val DIAGNOSTICS_SECTION_LAST_ERROR: String = "Last error"
const val DIAGNOSTICS_SECTION_REMEDIATION: String = "Recommended actions"

const val DIAGNOSTICS_EXPORT_BUTTON_LABEL: String = "Export diagnostics (JSON)"
const val DIAGNOSTICS_REFRESH_CONTENT_DESCRIPTION: String = "Refresh diagnostics"
const val DIAGNOSTICS_LOADING_MESSAGE: String = "Loading diagnostics\u2026"
const val DIAGNOSTICS_RETRY_LABEL: String = "Retry"
const val DIAGNOSTICS_NO_REMEDIATION_MESSAGE: String = "No action needed right now."
const val DIAGNOSTICS_NONE_AVAILABLE_MESSAGE: String = "No diagnostics available."
private const val DIAGNOSTICS_NO_ACTIVE_PROFILE: String = "None"

/**
 * Renders the local, on-device "Protection diagnostics" report. Purely a function of its
 * parameters -- it never reads [websnag.elopenmike.com.core.diagnostics.DiagnosticsRepository],
 * launches an intent, or performs a SAF write itself; every side effect (collecting a fresh
 * report, exporting, or acting on a [RemediationAction]) is delegated to the caller via callback
 * so this composable is fully testable with a directly-constructed [DiagnosticsReport] fixture.
 *
 * [report] is `null` until the first successful collection. [isLoading] and
 * [collectionErrorMessage] are surfaced independently of [report] so a stale report can stay on
 * screen while a background refresh is in flight or has failed -- a collection failure is never
 * papered over with a fabricated report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    report: DiagnosticsReport?,
    isLoading: Boolean,
    collectionErrorMessage: String?,
    onRefresh: () -> Unit,
    onExport: (DiagnosticsReport) -> Unit,
    onRemediationAction: (RemediationAction) -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(DIAGNOSTICS_SCREEN_TITLE) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = DIAGNOSTICS_REFRESH_CONTENT_DESCRIPTION)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isLoading) {
                item { Text(DIAGNOSTICS_LOADING_MESSAGE) }
            }
            if (collectionErrorMessage != null) {
                item {
                    Column {
                        Text(collectionErrorMessage, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRefresh) { Text(DIAGNOSTICS_RETRY_LABEL) }
                    }
                }
            }
            if (report != null) {
                diagnosticsReportContent(report, onExport, onRemediationAction)
            } else if (!isLoading && collectionErrorMessage == null) {
                item { Text(DIAGNOSTICS_NONE_AVAILABLE_MESSAGE) }
            }
        }
    }
}

private fun LazyListScope.diagnosticsReportContent(
    report: DiagnosticsReport,
    onExport: (DiagnosticsReport) -> Unit,
    onRemediationAction: (RemediationAction) -> Unit
) {
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_APP) {
            DiagnosticsRow("Version name: ${report.appBuildInfo.versionName}")
            DiagnosticsRow("Version code: ${report.appBuildInfo.versionCode}")
            DiagnosticsRow("Build type: ${report.appBuildInfo.buildType.name}")
            DiagnosticsRow("Schema version: ${report.schemaVersion}")
            DiagnosticsRow("Generated at: ${formatEpochMs(report.generatedAtEpochMs)}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_DEVICE) {
            DiagnosticsRow("API level: ${report.deviceInfo.apiLevel}")
            DiagnosticsRow("Manufacturer: ${report.deviceInfo.manufacturer}")
            DiagnosticsRow("Model: ${report.deviceInfo.model}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_NFC) {
            DiagnosticsRow("NFC present: ${yesNo(report.nfcHardwareState.present)}")
            DiagnosticsRow("NFC enabled: ${yesNo(report.nfcHardwareState.enabled)}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_ACCESSIBILITY) {
            DiagnosticsRow("Accessibility enabled: ${yesNo(report.accessibilityServiceState.enabled)}")
            DiagnosticsRow("Accessibility running: ${yesNo(report.accessibilityServiceState.running)}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_PERMISSIONS) {
            DiagnosticsRow("Notification permission: ${report.notificationPermissionState.name}")
            DiagnosticsRow("Exact alarm: ${report.exactAlarmCapability.name}")
            DiagnosticsRow("Battery optimization: ${report.batteryOptimizationState.name}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_SCHEDULE) {
            DiagnosticsRow(
                "Next transition: ${formatEpochMs(report.nextScheduledTransition.epochMs)} " +
                    "(${report.nextScheduledTransition.timingMode.name})"
            )
            DiagnosticsRow(
                "Last reconciliation: ${formatEpochMs(report.scheduleReconciliationStatus.lastReconciledEpochMs)} " +
                    "(${report.scheduleReconciliationStatus.outcome.name})"
            )
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_PROFILE_AND_TAG) {
            DiagnosticsRow("Active profile alias: ${report.activeProfileAlias.alias ?: DIAGNOSTICS_NO_ACTIVE_PROFILE}")
            DiagnosticsRow("Required tag: ${report.requiredEnrolledTagStatus.name}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_SECURITY) {
            DiagnosticsRow("Keystore key available: ${yesNo(report.keystoreKeyAvailable)}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_BACKUP) {
            DiagnosticsRow("Backup schema version: ${report.backupSchemaVersion}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_LAST_ERROR) {
            DiagnosticsRow("Last error category: ${report.lastError.category.name}")
            DiagnosticsRow("Last error time: ${formatEpochMs(report.lastError.occurredAtEpochMs)}")
        }
    }
    item {
        DiagnosticsSection(DIAGNOSTICS_SECTION_REMEDIATION) {
            if (report.remediationActions.isEmpty()) {
                Text(DIAGNOSTICS_NO_REMEDIATION_MESSAGE, style = MaterialTheme.typography.bodySmall)
            } else {
                report.remediationActions.forEach { action ->
                    Button(
                        onClick = { onRemediationAction(action) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(remediationActionLabel(action))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
    item {
        Button(onClick = { onExport(report) }, modifier = Modifier.fillMaxWidth()) {
            Text(DIAGNOSTICS_EXPORT_BUTTON_LABEL)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DiagnosticsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DiagnosticsRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
