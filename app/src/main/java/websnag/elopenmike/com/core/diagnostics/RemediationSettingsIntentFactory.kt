package websnag.elopenmike.com.core.diagnostics

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Sole mapping from a [RemediationAction] that opens a system settings screen to the concrete
 * [Intent] that opens it, extracted from `MainActivity` so the mapping is directly testable with
 * real Android framework types without launching an activity.
 *
 * [RemediationAction.OPEN_NFC_HUB], [RemediationAction.ENROLL_REQUIRED_TAG] and
 * [RemediationAction.RETRY_KEYSTORE_KEY_GENERATION] are deliberately app-navigation actions, not
 * settings actions: they always map to `null` here, and the caller is expected to route them
 * through its own `NavController` instead of starting an external settings [Intent].
 */
object RemediationSettingsIntentFactory {

    /**
     * The [Intent] that opens the settings screen [action] remediates, or `null` when [action] is
     * an in-app navigation action or is not available at [apiLevel] (defaults to
     * [Build.VERSION.SDK_INT]).
     */
    @SuppressLint("InlinedApi")
    fun intentFor(
        action: RemediationAction,
        packageName: String,
        apiLevel: Int = Build.VERSION.SDK_INT
    ): Intent? = when (action) {
        RemediationAction.ENABLE_NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
        RemediationAction.OPEN_ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        RemediationAction.OPEN_NOTIFICATION_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        RemediationAction.OPEN_EXACT_ALARM_SETTINGS ->
            if (apiLevel >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri())
            } else {
                null
            }
        RemediationAction.OPEN_BATTERY_OPTIMIZATION_SETTINGS ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        RemediationAction.OPEN_NFC_HUB,
        RemediationAction.ENROLL_REQUIRED_TAG,
        RemediationAction.RETRY_KEYSTORE_KEY_GENERATION -> null
    }
}
