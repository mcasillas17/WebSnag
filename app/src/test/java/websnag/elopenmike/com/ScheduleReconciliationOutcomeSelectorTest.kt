package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Test
import websnag.elopenmike.com.core.diagnostics.ReconciliationOutcome
import websnag.elopenmike.com.core.schedule.ScheduleReconciliationOutcomeSelector

/**
 * Pure unit tests for [ScheduleReconciliationOutcomeSelector], the outcome-classification helper
 * extracted from [websnag.elopenmike.com.core.schedule.ScheduleManager]'s active-occurrence
 * reconciliation branch. No Android/DataStore dependencies are involved -- these tests exercise
 * only the decision logic that used to collapse distinct "nothing changed" reasons into
 * [ReconciliationOutcome.NO_CHANGE].
 */
class ScheduleReconciliationOutcomeSelectorTest {

    @Test
    fun dismissedCurrentOccurrenceTakesPriorityOverEverythingElse() {
        assertEquals(
            ReconciliationOutcome.DISMISSED_CURRENT_OCCURRENCE,
            ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                isDismissedCurrentOccurrence = true,
                hasActiveProfile = false,
                activeProfileMatchesScheduledProfile = false,
                profileLookupSucceeded = false,
                activationSucceeded = false
            )
        )
        assertEquals(
            ReconciliationOutcome.DISMISSED_CURRENT_OCCURRENCE,
            ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                isDismissedCurrentOccurrence = true,
                hasActiveProfile = true,
                activeProfileMatchesScheduledProfile = true,
                profileLookupSucceeded = true,
                activationSucceeded = true
            )
        )
    }

    @Test
    fun noActiveProfileAndProfileLookupFailsIsProfileNotFound() {
        assertEquals(
            ReconciliationOutcome.PROFILE_NOT_FOUND,
            ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                isDismissedCurrentOccurrence = false,
                hasActiveProfile = false,
                activeProfileMatchesScheduledProfile = false,
                profileLookupSucceeded = false,
                activationSucceeded = false
            )
        )
    }

    @Test
    fun noActiveProfileAndActivationRejectedIsActivationRejected() {
        assertEquals(
            ReconciliationOutcome.ACTIVATION_REJECTED,
            ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                isDismissedCurrentOccurrence = false,
                hasActiveProfile = false,
                activeProfileMatchesScheduledProfile = false,
                profileLookupSucceeded = true,
                activationSucceeded = false
            )
        )
    }

    @Test
    fun noActiveProfileAndActivationSucceedsIsActivated() {
        assertEquals(
            ReconciliationOutcome.ACTIVATED,
            ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                isDismissedCurrentOccurrence = false,
                hasActiveProfile = false,
                activeProfileMatchesScheduledProfile = false,
                profileLookupSucceeded = true,
                activationSucceeded = true
            )
        )
    }

    @Test
    fun activeProfileMatchingScheduledProfileIsKeptActive() {
        assertEquals(
            ReconciliationOutcome.KEPT_ACTIVE,
            ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                isDismissedCurrentOccurrence = false,
                hasActiveProfile = true,
                activeProfileMatchesScheduledProfile = true,
                profileLookupSucceeded = true,
                activationSucceeded = true
            )
        )
    }

    @Test
    fun activeProfileNotMatchingScheduledProfileIsNoChange() {
        assertEquals(
            ReconciliationOutcome.NO_CHANGE,
            ScheduleReconciliationOutcomeSelector.selectActiveOccurrenceOutcome(
                isDismissedCurrentOccurrence = false,
                hasActiveProfile = true,
                activeProfileMatchesScheduledProfile = false,
                profileLookupSucceeded = true,
                activationSucceeded = true
            )
        )
    }
}
