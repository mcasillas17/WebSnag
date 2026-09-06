package websnag.elopenmike.com.core.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The user's explicit approval to convert a legacy unlock policy that
 * [LegacyTagIdentifierMigration] refuses to convert on its own.
 *
 * Deliberately process-scoped and payload-free. It is granted immediately before the retry it is
 * meant for, so it never needs to survive process death -- and must not: a persisted approval would
 * still be set after an unrelated abort and could then apply the irreversible replacement at some
 * later start, with no confirmation in front of the user at that moment. It also cannot live in the
 * migrating DataStore, which is exactly what is unreadable while the migration keeps failing.
 *
 * Reading and clearing are one operation on purpose. A separate read-then-clear pair invites
 * clearing it in the middle of a pass that still needs it.
 */
internal object MigrationRecoveryConsent {
    private val approved = AtomicBoolean(false)

    /** Records the approval; the next migration pass takes it. */
    fun approveLegacyUnlockConversion() {
        approved.set(true)
    }

    /** Takes the approval for one migration pass. It can never be spent twice. */
    fun takeLegacyUnlockApproval(): Boolean = approved.getAndSet(false)
}
