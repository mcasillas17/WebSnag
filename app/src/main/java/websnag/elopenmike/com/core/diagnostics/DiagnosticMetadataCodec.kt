package websnag.elopenmike.com.core.diagnostics

import kotlinx.serialization.json.Json

/**
 * Sole decode boundary for the typed diagnostics metadata records (currently
 * [ScheduleReconciliationRecord] and [LocalErrorRecord]) persisted by
 * [websnag.elopenmike.com.core.data.LocalDataStore]. A `null` [raw] means the preference key was
 * never written -- "no record" -- and decodes to `null`. A non-null [raw] that fails to parse is
 * a corrupted persisted record, not "no record": the original
 * [kotlinx.serialization.SerializationException] is left to propagate uncaught so data
 * corruption surfaces loudly instead of being silently treated as if it never existed.
 */
object DiagnosticMetadataCodec {
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true }

    inline fun <reified T> decode(raw: String?): T? {
        if (raw == null) return null
        return json.decodeFromString(raw)
    }
}
