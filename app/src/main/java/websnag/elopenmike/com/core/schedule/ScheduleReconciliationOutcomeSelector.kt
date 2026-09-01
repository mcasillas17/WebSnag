package websnag.elopenmike.com.core.schedule

import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome

/**
 * Pure classification of the [ReconciliationOutcome] for the "a schedule occurrence is currently
 * due" branch of [ScheduleManager]'s reconciliation pass. Extracted so the outcome-selection
 * logic can be unit tested without the Android/DataStore dependencies [ScheduleManager] itself
 * requires. Performs no I/O and triggers no side effects -- fetching the profile, attempting
 * activation, and persisting the occurrence remain the sole responsibility of [ScheduleManager];
 * this only classifies the already-computed result of that work.
 */
object ScheduleReconciliationOutcomeSelector {

    /**
     * @param isDismissedCurrentOccurrence true when the due occurrence was already dismissed by
     *   the user for this exact schedule/occurrence-start pair.
     * @param hasActiveProfile true when the enforcement engine currently has an active profile.
     * @param activeProfileMatchesScheduledProfile true when the currently active profile's id
     *   equals the due schedule's profile id. Ignored when [hasActiveProfile] is false.
     * @param profileLookupSucceeded true when the scheduled profile id resolved to a profile.
     *   Ignored when [hasActiveProfile] is true or [isDismissedCurrentOccurrence] is true.
     * @param activationSucceeded true when activation of the resolved profile was accepted by
     *   the enforcement engine. Ignored when [hasActiveProfile] is true, [isDismissedCurrentOccurrence]
     *   is true, or [profileLookupSucceeded] is false.
     */
    fun selectActiveOccurrenceOutcome(
        isDismissedCurrentOccurrence: Boolean,
        hasActiveProfile: Boolean,
        activeProfileMatchesScheduledProfile: Boolean,
        profileLookupSucceeded: Boolean,
        activationSucceeded: Boolean
    ): ReconciliationOutcome = when {
        isDismissedCurrentOccurrence -> ReconciliationOutcome.DISMISSED_CURRENT_OCCURRENCE
        !hasActiveProfile && !profileLookupSucceeded -> ReconciliationOutcome.PROFILE_NOT_FOUND
        !hasActiveProfile && !activationSucceeded -> ReconciliationOutcome.ACTIVATION_REJECTED
        !hasActiveProfile -> ReconciliationOutcome.ACTIVATED
        activeProfileMatchesScheduledProfile -> ReconciliationOutcome.KEPT_ACTIVE
        else -> ReconciliationOutcome.NO_CHANGE
    }
}
