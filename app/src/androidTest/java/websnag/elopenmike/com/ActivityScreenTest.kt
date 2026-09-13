package websnag.elopenmike.com

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import websnag.elopenmike.com.core.model.FocusSessionRecord
import websnag.elopenmike.com.ui.activity.ACTIVITY_BAR_TAG
import websnag.elopenmike.com.ui.activity.ActivityScreen
import websnag.elopenmike.com.ui.activity.ActivityViewModel
import websnag.elopenmike.com.ui.theme.WebSnagTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** Activity Week, Month and Year charts driven through the production ViewModel with synthetic history. */
class ActivityScreenTest {
    @get:Rule val composeRule = createComposeRule()

    // Wednesday 2025-03-05, noon UTC.
    private val now = at("2025-03-05T12:00")

    private fun at(text: String) = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun session(start: String, end: String) = FocusSessionRecord(
        id = start,
        profileId = "synthetic",
        profileName = "Synthetic Focus",
        startTimeEpochMs = at(start),
        endTimeEpochMs = at(end),
        durationSeconds = (at(end) - at(start)) / 1000
    )

    private val history = listOf(
        session("2025-03-03T09:00", "2025-03-03T10:00"),
        session("2025-03-04T23:00", "2025-03-05T01:00"),
        session("2025-01-15T09:00", "2025-01-15T11:30")
    )

    private fun viewModel(sessions: List<FocusSessionRecord> = history, activeStart: Long? = null) = ActivityViewModel(
        focusSessions = MutableStateFlow(sessions),
        activeSessionStart = flowOf(activeStart),
        savedStateHandle = SavedStateHandle(),
        clock = { Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC) },
        locale = { Locale.UK }
    )

    private fun show(
        viewModel: ActivityViewModel = viewModel(),
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        widthDp: Int? = null
    ) = composeRule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            WebSnagTheme(darkTheme = darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(if (widthDp == null) Modifier else Modifier.width(widthDp.dp)) {
                        ActivityScreen(viewModel)
                    }
                }
            }
        }
    }

    private fun scrollToChart() =
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(ACTIVITY_BAR_TAG))

    private fun assertBarCount(count: Int) {
        scrollToChart()
        composeRule.onAllNodesWithTag(ACTIVITY_BAR_TAG).assertCountEquals(count)
    }

    @Test fun opensOnTheCurrentWeekWithReadableSelectableBars() {
        show()
        composeRule.onNodeWithText("Mar 3 – Mar 9, 2025")
            .assertIsDisplayed()
            // Screen readers announce the new period after previous/next.
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithText("Week")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithContentDescription("Previous week").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Next week").assertIsNotEnabled()
        composeRule.onAllNodesWithText("This week").assertCountEquals(0)
        assertBarCount(7)
        composeRule.onNodeWithContentDescription("Monday, March 3: 1 hour of focus").assertHasClickAction()
        // The overnight session is split at midnight.
        composeRule.onNodeWithContentDescription("Tuesday, March 4: 1 hour of focus").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Wednesday, March 5: 1 hour of focus").assertIsSelected()
        composeRule.onNodeWithContentDescription("Thursday, March 6: upcoming").assertIsNotEnabled()
    }

    @Test fun selectingADayShowsItsTotalAndSessions() {
        show()
        scrollToChart()
        composeRule.onNodeWithContentDescription("Tuesday, March 4: 1 hour of focus").performClick()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Sessions for Tuesday, Mar 4"))
        composeRule.onNodeWithText("Total Focus: 1h 0m • 1 session").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tuesday, March 4: 1 hour of focus").assertIsSelected()
    }

    @Test fun previousNextAndCurrentNavigateByCalendarWeek() {
        show()
        composeRule.onNodeWithContentDescription("Previous week").performClick()
        composeRule.onNodeWithText("Feb 24 – Mar 2, 2025").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next week").assertIsEnabled()
        composeRule.onNodeWithText("This week").performClick()
        composeRule.onNodeWithText("Mar 3 – Mar 9, 2025").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next week").assertIsNotEnabled()
    }

    private fun assertBarsFit(widthDp: Int) {
        val limit = with(composeRule.density) { widthDp.dp.toPx() }
        composeRule.onAllNodesWithTag(ACTIVITY_BAR_TAG).fetchSemanticsNodes().forEach {
            assertTrue(it.boundsInRoot.width > 0f && it.boundsInRoot.right <= limit)
        }
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        // Whole label on one line, at its full unconstrained width.
        val layout = layouts.single()
        assertEquals("\"$text\" wraps", 1, layout.lineCount)
        assertTrue(
            "\"$text\" is clipped",
            ceil(layout.multiParagraph.maxIntrinsicWidth) <= layout.size.width
        )
    }

    @Test fun weekStaysReadableOnANarrowScreenWithLargeText() {
        show(fontScale = 2f, widthDp = 320)
        listOf("Week", "Month", "Year").forEach(::assertTextFits)
        assertBarCount(7)
        assertBarsFit(320)
        // The selected value is one wrapping caption instead of labels squeezed above each bar.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Wednesday, March 5 · 1h 0m"))
        composeRule.onNodeWithText("Wednesday, March 5 · 1h 0m")
            .assertIsDisplayed()
            // Its total ticks each minute during a session, so it must not re-announce itself.
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithContentDescription("Tuesday, March 4: 1 hour of focus").performClick()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Tuesday, March 4 · 1h 0m"))
        composeRule.onNodeWithText("Tuesday, March 4 · 1h 0m").assertIsDisplayed()
    }

    @Test fun monthKeepsEveryDaySelectableOnANarrowScreenWithLargeText() {
        show(fontScale = 2f, widthDp = 320)
        composeRule.onNodeWithText("Month").performClick()
        composeRule.onNodeWithText("March 2025").assertIsDisplayed()
        assertBarCount(31)
        // All 31 slots fit the narrow width without horizontal scrolling.
        assertBarsFit(320)
        // Future days keep a readable slot but cannot be selected; a past zero-value day can.
        composeRule.onNodeWithContentDescription("Tuesday, March 11: upcoming").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Sunday, March 2: 0 minutes of focus").apply {
            assertIsNotSelected()
            performClick()
            assertIsSelected()
        }
    }

    @Test fun draggingAcrossTheMonthChartMovesTheSelectedDay() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        val viewModel = viewModel()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WebSnagTheme { Box(Modifier.width(320.dp)) { ActivityScreen(viewModel) } }
            }
        }
        composeRule.onNodeWithText("Month").performClick()
        composeRule.onNodeWithContentDescription("Previous month").performClick()
        fun day(date: Int) = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.UK)
            .format(LocalDate.of(2025, 2, date)) + ": 0 minutes of focus"
        fun drag(fromDate: Int, toDate: Int) {
            scrollToChart()
            fun center(date: Int) = composeRule.onNodeWithContentDescription(day(date)).fetchSemanticsNode().boundsInRoot.center
            val from = center(fromDate)
            val to = center(toDate)
            composeRule.onRoot().performTouchInput { swipe(from, to, durationMillis = 400) }
        }

        drag(3, 10)
        composeRule.onNodeWithContentDescription(day(10)).assertIsSelected()
        composeRule.onNodeWithText("Monday, February 10 · 0m").assertIsDisplayed()

        // Right-to-left layouts mirror the bars, so the finger position must map to the mirrored day.
        direction = LayoutDirection.Rtl
        composeRule.waitForIdle()
        drag(3, 12)
        composeRule.onNodeWithContentDescription(day(12)).assertIsSelected()
    }

    @Test fun yearHasTwelveMonthsAndABarOpensThatMonth() {
        show()
        composeRule.onNodeWithText("Year").performClick()
        composeRule.onNodeWithText("2025").assertIsDisplayed()
        assertBarCount(12)
        composeRule.onNodeWithContentDescription("June 2025: upcoming").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("January 2025: 2 hours 30 minutes of focus").performClick()
        composeRule.onNodeWithText("January 2025").assertIsDisplayed()
        composeRule.onNodeWithText("Month").assertIsSelected()
        assertBarCount(31)
    }

    @Test fun allZeroHistoryRendersZeroBarsInDarkTheme() {
        show(viewModel(sessions = emptyList()), darkTheme = true)
        composeRule.onNodeWithText("Year").performClick()
        scrollToChart()
        composeRule.onNodeWithText("No recorded focus").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("March 2025: 0 minutes of focus").assertIsSelected()
        composeRule.onAllNodesWithTag(ACTIVITY_BAR_TAG).assertCountEquals(12)
    }

    @Test fun selectionSurvivesRecompositionAndThemeChange() {
        val viewModel = viewModel()
        var dark by mutableStateOf(false)
        composeRule.setContent {
            WebSnagTheme(darkTheme = dark) { ActivityScreen(viewModel) }
        }
        composeRule.onNodeWithText("Month").performClick()
        composeRule.onNodeWithContentDescription("Previous month").performClick()
        composeRule.onNodeWithText("February 2025").assertIsDisplayed()
        dark = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("February 2025").assertIsDisplayed()
        composeRule.onNodeWithText("Month").assertIsSelected()
    }

    @Test fun ongoingSessionCountsOnceInTheCurrentDay() {
        show(viewModel(sessions = emptyList(), activeStart = at("2025-03-05T11:30")))
        scrollToChart()
        composeRule.onNodeWithContentDescription("Wednesday, March 5: 30 minutes of focus").assertIsSelected()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("session in progress", substring = true))
        assertEquals(
            1,
            composeRule.onAllNodesWithText("Total Focus: 30m • 0 sessions • session in progress").fetchSemanticsNodes().size
        )
    }
}
