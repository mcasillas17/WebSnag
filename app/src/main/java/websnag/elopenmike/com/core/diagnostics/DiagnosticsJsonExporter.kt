package websnag.elopenmike.com.core.diagnostics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Typed failures for diagnostics export. Never wraps a partially-truncated payload: a report that
 * exceeds the size bound is rejected outright rather than silently trimmed.
 */
sealed class DiagnosticsExportException(message: String) : Exception(message) {
    class ExportTooLarge(val actualBytes: Int, val maxBytes: Int) :
        DiagnosticsExportException(
            "Diagnostics export size $actualBytes bytes exceeds the maximum of $maxBytes bytes."
        )

    /**
     * Thrown when an externally sourced display string (app version name, device manufacturer,
     * or device model) on the report to be exported contains control characters, path-like
     * separators, or exceeds [DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH]. This guards reports that
     * were constructed directly rather than through [DiagnosticsReportFactory], whose own
     * sanitization would normally prevent this. The export fails loudly rather than silently
     * truncating or redacting the offending value.
     */
    class UnsafeDisplayString(val fieldName: String) :
        DiagnosticsExportException(
            "Diagnostics export field '$fieldName' is unsafe or exceeds " +
                "$DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH characters."
        )
}

/**
 * Serializes a [DiagnosticsReport] to UTF-8 JSON. This is the sole intended export path for
 * diagnostics reports: it always encodes default values (so the field shape is stable regardless
 * of which fields happen to hold defaults) and enforces the hard size bound by throwing rather
 * than truncating.
 */
object DiagnosticsJsonExporter {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    /**
     * Returns the UTF-8 JSON encoding of [report].
     *
     * @throws DiagnosticsExportException.ExportTooLarge if the encoded payload exceeds
     * [DIAGNOSTICS_MAX_EXPORT_BYTES]. The payload is never truncated to fit.
     * @throws DiagnosticsExportException.UnsafeDisplayString if [report]'s app version name,
     * device manufacturer, or device model is unsafe (control characters or path-like separators)
     * or exceeds [DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH]. This guards against a report that was
     * constructed directly rather than through [DiagnosticsReportFactory] and so bypassed that
     * factory's sanitization; the value is never silently redacted or truncated on export.
     */
    fun export(report: DiagnosticsReport): String {
        val serialized = json.encodeToString(report)
        val byteSize = serialized.toByteArray(Charsets.UTF_8).size
        if (byteSize > DIAGNOSTICS_MAX_EXPORT_BYTES) {
            throw DiagnosticsExportException.ExportTooLarge(
                actualBytes = byteSize,
                maxBytes = DIAGNOSTICS_MAX_EXPORT_BYTES
            )
        }
        requireSafeDisplayString("appBuildInfo.versionName", report.appBuildInfo.versionName)
        requireSafeDisplayString("deviceInfo.manufacturer", report.deviceInfo.manufacturer)
        requireSafeDisplayString("deviceInfo.model", report.deviceInfo.model)
        return serialized
    }

    /**
     * Throws [DiagnosticsExportException.UnsafeDisplayString] if [value] contains control
     * characters, path-like separators, or exceeds [DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH].
     */
    private fun requireSafeDisplayString(fieldName: String, value: String) {
        val isUnsafe = value.any { it.isISOControl() } || value.contains('/') || value.contains('\\')
        val isOverlong = value.length > DIAGNOSTICS_MAX_DISPLAY_STRING_LENGTH
        if (isUnsafe || isOverlong) {
            throw DiagnosticsExportException.UnsafeDisplayString(fieldName)
        }
    }
}
