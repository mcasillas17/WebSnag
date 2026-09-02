package websnag.elopenmike.com.core.diagnostics

import kotlinx.serialization.Serializable

/**
 * Persisted record of a single schedule reconciliation pass. Carries only a timestamp and a
 * typed [ReconciliationOutcome] -- never a schedule id, profile id, or any other payload -- so it
 * can be stored, listed, and fed into diagnostics without risk of leaking user-specific data.
 */
@Serializable
data class ScheduleReconciliationRecord(
    val timestampEpochMs: Long,
    val outcome: ReconciliationOutcome
)

/**
 * Persisted record of a single local error observation. Carries only a timestamp and a typed
 * [ErrorCategory] -- never an exception message, stack trace, or any other payload -- so it can be
 * stored and fed into diagnostics without risk of leaking user-specific or sensitive data.
 */
@Serializable
data class LocalErrorRecord(
    val timestampEpochMs: Long,
    val category: ErrorCategory
)
