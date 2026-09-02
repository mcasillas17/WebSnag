package websnag.elopenmike.com

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.diagnostics.RemediationAction
import websnag.elopenmike.com.ui.diagnostics.formatEpochMs
import websnag.elopenmike.com.ui.diagnostics.remediationActionLabel
import websnag.elopenmike.com.ui.diagnostics.yesNo

/**
 * Pure, Android-independent unit tests for the display helpers backing
 * [websnag.elopenmike.com.ui.diagnostics.DiagnosticsScreen]: a per-[RemediationAction] button
 * label, a locale/timezone-independent timestamp formatter, and a boolean-to-word mapper. None of
 * these touch Compose or any Android framework class, so they run on the plain JVM.
 */
class DiagnosticsPresentationTest {

    @Test
    fun remediationActionLabelIsNonBlankForEveryAction() {
        RemediationAction.values().forEach { action ->
            assertTrue(
                "label for $action must not be blank",
                remediationActionLabel(action).isNotBlank()
            )
        }
    }

    @Test
    fun remediationActionLabelIsDistinctPerAction() {
        val labels = RemediationAction.values().map(::remediationActionLabel)
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun formatEpochMsReturnsNeverForAnAbsentTimestamp() {
        assertEquals("Never", formatEpochMs(null))
    }

    @Test
    fun formatEpochMsIsDeterministicAndLocaleTimezoneIndependent() {
        // 1_700_000_000_000 ms == 2023-11-14T22:13:20Z, verified independent of any device/JVM
        // default locale or timezone since the formatter is fixed to UTC.
        assertEquals("2023-11-14 22:13 UTC", formatEpochMs(1_700_000_000_000L))
    }

    @Test
    fun yesNoMapsBooleanToTheExpectedWord() {
        assertEquals("Yes", yesNo(true))
        assertEquals("No", yesNo(false))
    }
}
