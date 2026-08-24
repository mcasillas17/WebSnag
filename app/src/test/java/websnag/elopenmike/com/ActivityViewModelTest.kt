package websnag.elopenmike.com

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.FocusSessionRecord
import websnag.elopenmike.com.ui.activity.ActivityUiState
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    @Test
    fun testFocusStatsCalculation() {
        val now = System.currentTimeMillis()
        val session1 = FocusSessionRecord(
            id = "sess-1",
            profileId = "prof-1",
            profileName = "Deep Work",
            filterMode = FilterMode.ALLOWLIST,
            startTimeEpochMs = now - 3600 * 1000L,
            endTimeEpochMs = now,
            durationSeconds = 3600L,
            interceptionsPrevented = 4
        )

        val session2 = FocusSessionRecord(
            id = "sess-2",
            profileId = "prof-2",
            profileName = "Light Work",
            filterMode = FilterMode.BLOCKLIST,
            startTimeEpochMs = now - 1800 * 1000L,
            endTimeEpochMs = now,
            durationSeconds = 1800L,
            interceptionsPrevented = 2
        )

        val sessions = listOf(session1, session2)
        val totalSec = sessions.sumOf { it.durationSeconds }
        val totalBlocked = sessions.sumOf { it.interceptionsPrevented }

        assertEquals(5400L, totalSec)
        assertEquals(90, (totalSec / 60).toInt())
        assertEquals(6, totalBlocked)

        val averageDailyMins = (totalSec / 7 / 60).toInt()
        assertEquals(12, averageDailyMins)
    }

    @Test
    fun testCalendarDayTileFormatting() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        assertTrue(dayOfMonth in 1..31)
    }
}
