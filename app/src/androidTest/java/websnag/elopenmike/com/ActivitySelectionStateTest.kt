package websnag.elopenmike.com

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where the selected Activity period comes from in the real MainActivity: its own saved state
 * survives recreation (rotation, theme changes), while launch-intent extras from other apps can
 * neither preset nor break it.
 */
class ActivitySelectionStateTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private fun openActivityTab() = composeRule.onNode(hasText("Activity") and hasClickAction()).performClick()

    @Test fun selectedPeriodSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openActivityTab()
            composeRule.onNodeWithText("Month").performClick()
            composeRule.onNodeWithContentDescription("Previous month").performClick()
            val previousMonth = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
                .format(YearMonth.now().minusMonths(1))
            composeRule.onNodeWithText(previousMonth).assertIsDisplayed()

            scenario.recreate()

            composeRule.onNodeWithText(previousMonth).assertIsDisplayed()
            composeRule.onNodeWithText("Month").assertIsSelected()
        }
    }

    @Test fun launchIntentExtrasCannotPresetOrBreakTheSelection() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("activity_granularity", "YEAR")
            .putExtra("activity_selected_epoch_day", "not a day")
        ActivityScenario.launch<MainActivity>(intent).use {
            openActivityTab()
            composeRule.onNodeWithText("Week").assertIsSelected()
        }
    }
}
