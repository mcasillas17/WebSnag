package websnag.elopenmike.com

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import websnag.elopenmike.com.core.activity.ActivityGranularity
import websnag.elopenmike.com.core.model.FocusSessionRecord
import websnag.elopenmike.com.ui.activity.ActivitySelection
import websnag.elopenmike.com.ui.activity.ActivityViewModel
import websnag.elopenmike.com.ui.activity.buildActivityUiState
import websnag.elopenmike.com.ui.activity.formatFocusDuration
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

private const val MINUTE = 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    private val zone = ZoneId.of("America/New_York")

    // Wednesday 2025-03-05, noon.
    private val now = at("2025-03-05T12:00")
    private val today = LocalDate.parse("2025-03-05")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: String, end: String, id: String = start) = FocusSessionRecord(
        id = id,
        profileId = "p",
        profileName = "Synthetic",
        startTimeEpochMs = at(start),
        endTimeEpochMs = at(end),
        durationSeconds = (at(end) - at(start)) / 1000
    )

    private fun state(
        selection: ActivitySelection = ActivitySelection(),
        sessions: List<FocusSessionRecord> = emptyList(),
        activeStart: Long? = null,
        locale: Locale = Locale.UK
    ) = buildActivityUiState(sessions, activeStart, selection, now, zone, locale)

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = ActivityViewModel(
        focusSessions = emptyFlow(),
        activeSessionStart = emptyFlow(),
        savedStateHandle = handle,
        clock = { Clock.fixed(java.time.Instant.ofEpochMilli(now), zone) },
        locale = { Locale.UK }
    )

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(mainDispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun opensOnTheCurrentLocaleWeekWithTodaySelected() {
        val uk = state()
        assertEquals(ActivityGranularity.WEEK, uk.granularity)
        assertEquals(7, uk.bars.size)
        assertEquals(LocalDate.parse("2025-03-03"), uk.bars.first().date)
        assertTrue(uk.isCurrentPeriod)
        assertEquals(listOf(today), uk.bars.filter { it.isSelected }.map { it.date })

        val us = state(locale = Locale.US)
        assertEquals(LocalDate.parse("2025-03-02"), us.bars.first().date)
    }

    @Test fun monthAndYearHaveTheirExactBarCounts() {
        assertEquals(31, state(ActivitySelection(ActivityGranularity.MONTH)).bars.size)
        assertEquals(28, state(ActivitySelection(ActivityGranularity.MONTH, LocalDate.parse("2025-02-10"))).bars.size)
        assertEquals(29, state(ActivitySelection(ActivityGranularity.MONTH, LocalDate.parse("2024-02-10"))).bars.size)
        assertEquals(30, state(ActivitySelection(ActivityGranularity.MONTH, LocalDate.parse("2024-11-10"))).bars.size)
        assertEquals(12, state(ActivitySelection(ActivityGranularity.YEAR)).bars.size)
    }

    @Test fun allZeroChartsHaveNoFakeHeightOrInvalidScale() {
        for (granularity in ActivityGranularity.entries) {
            val empty = state(ActivitySelection(granularity))
            assertTrue(empty.bars.all { it.focusMs == 0L && it.heightFraction == 0f })
            assertEquals(0L, empty.totalFocusMs)
            assertEquals(0L, empty.dailyAverageFocusMs)
        }
    }

    @Test fun barsScaleToThePeriodMaximum() {
        val week = state(
            sessions = listOf(
                session("2025-03-03T09:00", "2025-03-03T10:00", "a"),
                session("2025-03-04T09:00", "2025-03-04T11:00", "b")
            )
        )
        assertEquals(listOf(0.5f, 1f, 0f, 0f, 0f, 0f, 0f), week.bars.map { it.heightFraction })
        assertEquals("Tuesday, March 4: 2 hours of focus", week.bars[1].description)
    }

    @Test fun subMinuteFocusIsNeverPresentedAsZero() {
        val brief = state(sessions = listOf(session("2025-03-05T09:00:00", "2025-03-05T09:00:30")))
        assertEquals(1f, brief.bars[2].heightFraction)
        assertEquals("Wednesday, March 5: less than a minute of focus", brief.bars[2].description)
        assertEquals("Wednesday, March 5 · <1m", brief.selectedBarSummary)
        assertEquals("<1m", formatFocusDuration(brief.totalFocusMs))
        assertEquals("0m", formatFocusDuration(0))
    }

    @Test fun futureDaysAreZeroAndMarkedUpcoming() {
        val week = state(sessions = listOf(session("2025-03-05T11:00", "2025-03-08T11:00")))
        assertEquals(listOf(false, false, false, true, true, true, true), week.bars.map { it.isFuture })
        assertEquals(listOf(0L, 0L, 3_600_000L, 0L, 0L, 0L, 0L), week.bars.map { it.focusMs })
    }

    @Test fun totalsAndAveragesMatchTheStatedPeriod() {
        val sessions = listOf(
            session("2025-03-03T09:00", "2025-03-03T12:00", "a"),
            session("2025-02-10T09:00", "2025-02-10T23:00", "b")
        )
        val week = state(sessions = sessions)
        assertEquals("Week total", week.totalLabel)
        assertEquals(180 * MINUTE, week.totalFocusMs)
        // Monday through today (Wednesday): three elapsed days.
        assertEquals(3, week.averageDays)
        assertEquals(60 * MINUTE, week.dailyAverageFocusMs)

        val february = state(ActivitySelection(ActivityGranularity.MONTH, LocalDate.parse("2025-02-10")), sessions)
        assertEquals("Month total", february.totalLabel)
        assertEquals(14 * 60 * MINUTE, february.totalFocusMs)
        assertEquals(28, february.averageDays)
        assertEquals(30 * MINUTE, february.dailyAverageFocusMs)

        val year = state(ActivitySelection(ActivityGranularity.YEAR), sessions)
        assertEquals("Year total", year.totalLabel)
        assertEquals(17 * 60 * MINUTE, year.totalFocusMs)
        assertEquals(64, year.averageDays)
    }

    @Test fun periodLabelsNameTheCalendarPeriod() {
        assertEquals("Mar 3 – Mar 9, 2025", state().periodLabel)
        assertEquals(
            "Dec 30, 2024 – Jan 5, 2025",
            state(ActivitySelection(ActivityGranularity.WEEK, LocalDate.parse("2025-01-01"))).periodLabel
        )
        assertEquals("March 2025", state(ActivitySelection(ActivityGranularity.MONTH)).periodLabel)
        assertEquals("2025", state(ActivitySelection(ActivityGranularity.YEAR)).periodLabel)
    }

    @Test fun selectedDayShowsItsShareOfOvernightSessions() {
        val overnight = session("2025-03-03T23:00", "2025-03-04T01:30")
        val tuesday = state(ActivitySelection(selectedDate = LocalDate.parse("2025-03-04")), listOf(overnight))
        assertEquals("Tuesday, Mar 4", tuesday.selectedDayLabel)
        assertEquals(90 * MINUTE, tuesday.selectedDayFocusMs)
        assertEquals(listOf(overnight), tuesday.selectedDaySessions)
        val monday = state(ActivitySelection(selectedDate = LocalDate.parse("2025-03-03")), listOf(overnight))
        assertEquals(60 * MINUTE, monday.selectedDayFocusMs)
        assertEquals(listOf(overnight), monday.selectedDaySessions)
    }

    @Test fun daySessionsMatchTheDayTotalEvenForReversedRecords() {
        // A device clock moved backwards mid-session can persist end < start; it adds no focus time,
        // so it must not be listed as one of the day's sessions either.
        val reversed = session("2025-03-04T10:00", "2025-03-04T09:00")
        val tuesday = state(ActivitySelection(selectedDate = LocalDate.parse("2025-03-04")), listOf(reversed))
        assertEquals(0L, tuesday.selectedDayFocusMs)
        assertEquals(emptyList<FocusSessionRecord>(), tuesday.selectedDaySessions)
    }

    @Test fun ongoingSessionCountsTodayOnceAndIsFlagged() {
        val start = at("2025-03-05T11:00")
        val ongoing = state(activeStart = start)
        assertEquals("Today, Mar 5", ongoing.selectedDayLabel)
        assertEquals(60 * MINUTE, ongoing.selectedDayFocusMs)
        assertTrue(ongoing.selectedDayHasOngoingSession)

        val completed = state(sessions = listOf(session("2025-03-05T11:00", "2025-03-05T12:00")), activeStart = start)
        assertEquals(60 * MINUTE, completed.totalFocusMs)
        assertFalse(completed.selectedDayHasOngoingSession)
    }

    @Test fun yearViewHasNoDayDrillDown() {
        val year = state(ActivitySelection(ActivityGranularity.YEAR))
        assertNull(year.selectedDayLabel)
        assertEquals(listOf(LocalDate.parse("2025-03-01")), year.bars.filter { it.isSelected }.map { it.date })
    }

    @Test fun streakCountsConsecutiveDaysWithFocus() {
        val sessions = listOf(
            session("2025-03-04T09:00", "2025-03-04T10:00", "a"),
            session("2025-03-03T09:00", "2025-03-03T10:00", "b"),
            session("2025-03-01T09:00", "2025-03-01T10:00", "c")
        )
        assertEquals(2, state(sessions = sessions).currentStreakDays)
        assertEquals(3, state(sessions = sessions, activeStart = at("2025-03-05T11:00")).currentStreakDays)
    }

    @Test fun weekAxisUsesNarrowDayNamesSoLargeTextFits() {
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), state().bars.map { it.axisLabel })
    }

    @Test fun selectedBarIsSummarizedForSelectionFeedback() {
        val week = state(sessions = listOf(session("2025-03-05T09:00", "2025-03-05T10:30")))
        assertEquals("Wednesday, March 5 · 1h 30m", week.selectedBarSummary)
        assertEquals("March 2025 · 1h 30m", state(ActivitySelection(ActivityGranularity.YEAR), week.selectedDaySessions).selectedBarSummary)
    }

    @Test fun visibleValuesRefreshEachMinuteAndRollOverAtMidnight() = runTest(mainDispatcher) {
        // Sunday 23:59 ends the UK week; an ongoing session started at 23:00.
        var nowMs = at("2025-03-09T23:59")
        val vm = ActivityViewModel(
            focusSessions = MutableStateFlow(emptyList()),
            activeSessionStart = MutableStateFlow(at("2025-03-09T23:00")),
            savedStateHandle = SavedStateHandle(),
            clock = { Clock.fixed(Instant.ofEpochMilli(nowMs), zone) },
            locale = { Locale.UK }
        )
        val subscription = launch { vm.uiState.collect {} }
        assertEquals("Mar 3 – Mar 9, 2025", vm.uiState.value.periodLabel)
        assertEquals(59 * MINUTE, vm.uiState.value.selectedDayFocusMs)

        nowMs = at("2025-03-10T00:01")
        advanceTimeBy(60_001)
        assertEquals("Mar 10 – Mar 16, 2025", vm.uiState.value.periodLabel)
        assertEquals("Today, Mar 10", vm.uiState.value.selectedDayLabel)
        assertEquals(1 * MINUTE, vm.uiState.value.selectedDayFocusMs)
        subscription.cancel()
    }

    @Test fun navigationMovesByCalendarPeriodAndStopsAtTheCurrentOne() {
        val vm = viewModel()
        assertTrue(vm.uiState.value.isCurrentPeriod)
        vm.showNextPeriod()
        assertEquals(ActivitySelection(), vm.selection.value)

        vm.showPreviousPeriod()
        assertEquals(LocalDate.parse("2025-02-26"), vm.selection.value.selectedDate)
        vm.selectGranularity(ActivityGranularity.MONTH)
        assertEquals(LocalDate.parse("2025-02-26"), vm.selection.value.selectedDate)
        vm.showPreviousPeriod()
        assertEquals(LocalDate.parse("2025-01-26"), vm.selection.value.selectedDate)
        vm.selectGranularity(ActivityGranularity.YEAR)
        vm.showPreviousPeriod()
        assertEquals(LocalDate.parse("2024-01-26"), vm.selection.value.selectedDate)
        vm.showNextPeriod()
        vm.showNextPeriod()
        // Never beyond the current period.
        assertEquals(LocalDate.parse("2025-01-26"), vm.selection.value.selectedDate)
        vm.showCurrentPeriod()
        assertEquals(ActivitySelection(ActivityGranularity.YEAR), vm.selection.value)
    }

    @Test fun returningToTodayFollowsTodayAgain() {
        val vm = viewModel()
        vm.showPreviousPeriod()
        vm.showNextPeriod()
        assertEquals(ActivitySelection(), vm.selection.value)
    }

    @Test fun monthNavigationClampsToShorterMonths() {
        val vm = viewModel()
        vm.selectGranularity(ActivityGranularity.MONTH)
        vm.selectBar(LocalDate.parse("2025-03-01"))
        vm.showPreviousPeriod()
        vm.showPreviousPeriod()
        vm.selectBar(LocalDate.parse("2025-01-31"))
        vm.showNextPeriod()
        assertEquals(LocalDate.parse("2025-02-28"), vm.selection.value.selectedDate)
    }

    @Test fun selectingBarsDrillsDownAndIgnoresFutureDays() {
        val vm = viewModel()
        vm.selectBar(LocalDate.parse("2025-03-04"))
        assertEquals(LocalDate.parse("2025-03-04"), vm.selection.value.selectedDate)
        vm.selectBar(LocalDate.parse("2025-03-07"))
        assertEquals(LocalDate.parse("2025-03-04"), vm.selection.value.selectedDate)

        vm.selectGranularity(ActivityGranularity.YEAR)
        vm.selectBar(LocalDate.parse("2025-01-01"))
        assertEquals(ActivitySelection(ActivityGranularity.MONTH, LocalDate.parse("2025-01-01")), vm.selection.value)
        vm.selectGranularity(ActivityGranularity.YEAR)
        vm.selectBar(LocalDate.parse("2025-03-01"))
        assertEquals(ActivitySelection(ActivityGranularity.MONTH), vm.selection.value)
        vm.selectGranularity(ActivityGranularity.YEAR)
        vm.selectBar(LocalDate.parse("2025-06-01"))
        assertEquals(ActivityGranularity.YEAR, vm.selection.value.granularity)
    }

    @Test fun malformedOrForeignSavedStateFallsBackToTheCurrentWeek() {
        listOf(
            mapOf("activity_granularity" to 7, "activity_selected_epoch_day" to "not a day"),
            mapOf("activity_granularity" to "SOMETHING", "activity_selected_epoch_day" to Long.MAX_VALUE),
            mapOf("activity_selected_epoch_day" to LocalDate.MIN.toEpochDay()),
            mapOf("activity_selected_epoch_day" to LocalDate.parse("2025-03-06").toEpochDay())
        ).forEach { values ->
            val restored = viewModel(SavedStateHandle(values))
            assertEquals(ActivitySelection(), restored.selection.value)
            assertEquals("Mar 3 – Mar 9, 2025", restored.uiState.value.periodLabel)
        }
    }

    @Test fun selectionSurvivesRecreationFromSavedState() {
        val handle = SavedStateHandle()
        viewModel(handle).apply {
            selectGranularity(ActivityGranularity.MONTH)
            showPreviousPeriod()
        }
        val restored = viewModel(handle)
        assertEquals(ActivitySelection(ActivityGranularity.MONTH, LocalDate.parse("2025-02-05")), restored.selection.value)
        assertEquals("February 2025", restored.uiState.value.periodLabel)
    }
}
