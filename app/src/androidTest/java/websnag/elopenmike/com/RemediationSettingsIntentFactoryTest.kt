package websnag.elopenmike.com

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.diagnostics.RemediationAction
import websnag.elopenmike.com.core.diagnostics.RemediationSettingsIntentFactory

private const val FIXTURE_PACKAGE_NAME = "websnag.elopenmike.com.test.fixture"

/**
 * Instrumented proof of every [RemediationAction] -> [Intent] mapping
 * [RemediationSettingsIntentFactory] produces, using real [Intent]/[Settings]/[Uri] Android
 * framework types (unavailable on a plain JVM unit test). Never launches an activity/settings
 * screen -- only the constructed [Intent]'s action/data/extras are asserted.
 */
@RunWith(AndroidJUnit4::class)
class RemediationSettingsIntentFactoryTest {

    @Test
    fun enableNfcMapsToTheNfcSettingsAction() {
        val intent = RemediationSettingsIntentFactory.intentFor(
            RemediationAction.ENABLE_NFC,
            FIXTURE_PACKAGE_NAME,
            apiLevel = Build.VERSION_CODES.TIRAMISU
        )

        assertEquals(Settings.ACTION_NFC_SETTINGS, intent?.action)
    }

    @Test
    fun openAccessibilitySettingsMapsToTheAccessibilitySettingsAction() {
        val intent = RemediationSettingsIntentFactory.intentFor(
            RemediationAction.OPEN_ACCESSIBILITY_SETTINGS,
            FIXTURE_PACKAGE_NAME,
            apiLevel = Build.VERSION_CODES.TIRAMISU
        )

        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent?.action)
    }

    @Test
    fun openNotificationSettingsMapsToTheAppNotificationSettingsActionWithThePackageExtra() {
        val intent = RemediationSettingsIntentFactory.intentFor(
            RemediationAction.OPEN_NOTIFICATION_SETTINGS,
            FIXTURE_PACKAGE_NAME,
            apiLevel = Build.VERSION_CODES.TIRAMISU
        )

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent?.action)
        assertEquals(FIXTURE_PACKAGE_NAME, intent?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun openExactAlarmSettingsOnApiSOrAboveMapsToThePackageUriAction() {
        val intent = RemediationSettingsIntentFactory.intentFor(
            RemediationAction.OPEN_EXACT_ALARM_SETTINGS,
            FIXTURE_PACKAGE_NAME,
            apiLevel = Build.VERSION_CODES.S
        )

        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent?.action)
        assertEquals("package:$FIXTURE_PACKAGE_NAME", intent?.data.toString())
    }

    @Test
    fun openExactAlarmSettingsBelowApiSProducesNoIntent() {
        val intent = RemediationSettingsIntentFactory.intentFor(
            RemediationAction.OPEN_EXACT_ALARM_SETTINGS,
            FIXTURE_PACKAGE_NAME,
            apiLevel = Build.VERSION_CODES.R
        )

        assertNull(intent)
    }

    @Test
    fun openBatteryOptimizationSettingsMapsToThePackageUriAction() {
        val intent = RemediationSettingsIntentFactory.intentFor(
            RemediationAction.OPEN_BATTERY_OPTIMIZATION_SETTINGS,
            FIXTURE_PACKAGE_NAME,
            apiLevel = Build.VERSION_CODES.TIRAMISU
        )

        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent?.action)
        assertEquals("package:$FIXTURE_PACKAGE_NAME", intent?.data.toString())
    }

    @Test
    fun theThreeAppNavigationActionsProduceNoSettingsIntentAndAreLeftToNavController() {
        listOf(
            RemediationAction.OPEN_NFC_HUB,
            RemediationAction.ENROLL_REQUIRED_TAG,
            RemediationAction.RETRY_KEYSTORE_KEY_GENERATION
        ).forEach { action ->
            val intent = RemediationSettingsIntentFactory.intentFor(
                action,
                FIXTURE_PACKAGE_NAME,
                apiLevel = Build.VERSION_CODES.TIRAMISU
            )

            assertNull("expected no settings intent for $action", intent)
        }
    }
}
