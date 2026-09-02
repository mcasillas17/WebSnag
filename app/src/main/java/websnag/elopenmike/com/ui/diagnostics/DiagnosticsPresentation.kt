package websnag.elopenmike.com.ui.diagnostics

import websnag.elopenmike.com.core.diagnostics.RemediationAction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure, Android/Compose-independent display helpers for [DiagnosticsScreen]. Kept in a separate
 * file so they can be unit tested on the plain JVM without pulling in any Compose runtime class.
 */

/**
 * Human-facing label for the button that triggers [action]. Exhaustive over [RemediationAction]
 * so a newly added action fails to compile here rather than silently rendering no label.
 */
fun remediationActionLabel(action: RemediationAction): String = when (action) {
    RemediationAction.OPEN_NOTIFICATION_SETTINGS -> "Open notification settings"
    RemediationAction.OPEN_ACCESSIBILITY_SETTINGS -> "Open accessibility settings"
    RemediationAction.OPEN_BATTERY_OPTIMIZATION_SETTINGS -> "Open battery optimization settings"
    RemediationAction.OPEN_EXACT_ALARM_SETTINGS -> "Open exact alarm settings"
    RemediationAction.ENABLE_NFC -> "Enable NFC"
    RemediationAction.ENROLL_REQUIRED_TAG -> "Enroll required tag"
    RemediationAction.RETRY_KEYSTORE_KEY_GENERATION -> "Retry keystore key generation"
    RemediationAction.OPEN_NFC_HUB -> "Open NFC Hub"
}

/** Fixed UTC formatter so diagnostics timestamps render identically regardless of device locale/timezone. */
private val DIAGNOSTICS_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/**
 * Deterministic, locale/timezone-independent display of [epochMs], or `"Never"` when it is `null`
 * (a legitimate "this has not happened yet" state for diagnostics fields, never an error).
 */
fun formatEpochMs(epochMs: Long?): String =
    if (epochMs == null) "Never" else DIAGNOSTICS_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(epochMs))

/** "Yes"/"No" display for a diagnostics boolean field. */
fun yesNo(value: Boolean): String = if (value) "Yes" else "No"
