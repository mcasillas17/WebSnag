package websnag.elopenmike.com

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.diagnostics.LocalErrorRecord
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.diagnostics.ScheduleReconciliationRecord

/**
 * Shape and round-trip tests for the typed diagnostics event records persisted by
 * [websnag.elopenmike.com.core.data.LocalDataStore]. Both records must serialize to exactly a
 * timestamp plus one typed enum field -- never a free-form payload/message/value field -- and
 * every enum member must round-trip losslessly.
 */
class DiagnosticsMetadataTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun scheduleReconciliationRecordHasOnlyTimestampAndTypedOutcomeFields() {
        val record = ScheduleReconciliationRecord(
            timestampEpochMs = 1_700_000_000_000L,
            outcome = ReconciliationOutcome.ACTIVATED
        )

        val encoded = json.encodeToString(record)
        val keys = (Json.parseToJsonElement(encoded) as JsonObject).keys

        assertEquals(setOf("timestampEpochMs", "outcome"), keys)
    }

    @Test
    fun scheduleReconciliationRecordRoundTripsEveryOutcomeValue() {
        ReconciliationOutcome.values().forEach { outcome ->
            val record = ScheduleReconciliationRecord(timestampEpochMs = 42L, outcome = outcome)
            val decoded = json.decodeFromString<ScheduleReconciliationRecord>(json.encodeToString(record))
            assertEquals(record, decoded)
        }
    }

    @Test
    fun localErrorRecordHasOnlyTimestampAndTypedCategoryFields() {
        val record = LocalErrorRecord(
            timestampEpochMs = 1_700_000_000_000L,
            category = ErrorCategory.SCHEDULE
        )

        val encoded = json.encodeToString(record)
        val keys = (Json.parseToJsonElement(encoded) as JsonObject).keys

        assertEquals(setOf("timestampEpochMs", "category"), keys)
    }

    @Test
    fun localErrorRecordRoundTripsEveryCategoryValue() {
        ErrorCategory.values().forEach { category ->
            val record = LocalErrorRecord(timestampEpochMs = 7L, category = category)
            val decoded = json.decodeFromString<LocalErrorRecord>(json.encodeToString(record))
            assertEquals(record, decoded)
        }
    }
}
