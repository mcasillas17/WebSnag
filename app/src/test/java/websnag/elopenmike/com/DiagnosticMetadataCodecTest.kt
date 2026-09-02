package websnag.elopenmike.com

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import websnag.elopenmike.com.core.diagnostics.DiagnosticMetadataCodec
import websnag.elopenmike.com.core.diagnostics.ErrorCategory
import websnag.elopenmike.com.core.diagnostics.LocalErrorRecord
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.diagnostics.ScheduleReconciliationRecord

/**
 * [DiagnosticMetadataCodec] is the sole decode boundary [websnag.elopenmike.com.core.data.LocalDataStore]
 * uses for the typed diagnostics metadata records. It must tell apart "no record was ever
 * persisted" (the preference key is absent, raw == null) from "a record was persisted but is
 * corrupted" (malformed JSON) -- the latter must fail loudly with the original
 * [SerializationException] rather than be silently swallowed and treated as the former.
 */
class DiagnosticMetadataCodecTest {

    @Test
    fun decodeReturnsNullForAbsentScheduleReconciliationRecord() {
        assertNull(DiagnosticMetadataCodec.decode<ScheduleReconciliationRecord>(null))
    }

    @Test
    fun decodeReturnsTypedScheduleReconciliationRecordForValidJson() {
        val record = ScheduleReconciliationRecord(
            timestampEpochMs = 1_700_000_000_000L,
            outcome = ReconciliationOutcome.ACTIVATED
        )
        val raw = Json.encodeToString(record)

        assertEquals(record, DiagnosticMetadataCodec.decode<ScheduleReconciliationRecord>(raw))
    }

    @Test(expected = SerializationException::class)
    fun decodeThrowsSerializationExceptionForMalformedScheduleReconciliationRecordJson() {
        DiagnosticMetadataCodec.decode<ScheduleReconciliationRecord>("{not valid json")
    }

    @Test
    fun decodeReturnsNullForAbsentLocalErrorRecord() {
        assertNull(DiagnosticMetadataCodec.decode<LocalErrorRecord>(null))
    }

    @Test
    fun decodeReturnsTypedLocalErrorRecordForValidJson() {
        val record = LocalErrorRecord(timestampEpochMs = 7L, category = ErrorCategory.SCHEDULE)
        val raw = Json.encodeToString(record)

        assertEquals(record, DiagnosticMetadataCodec.decode<LocalErrorRecord>(raw))
    }

    @Test(expected = SerializationException::class)
    fun decodeThrowsSerializationExceptionForMalformedLocalErrorRecordJson() {
        DiagnosticMetadataCodec.decode<LocalErrorRecord>("{not valid json")
    }

    @Test(expected = SerializationException::class)
    fun decodeThrowsSerializationExceptionForWellFormedButWrongShapeJson() {
        // Syntactically valid JSON but missing required fields must still fail loudly -- it must
        // not decode to a garbage/default record and must not be swallowed as "absent".
        DiagnosticMetadataCodec.decode<LocalErrorRecord>("{}")
    }
}
