package websnag.elopenmike.com.core.diagnostics

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import websnag.elopenmike.com.core.data.AndroidKeystoreTagIdentityProtector
import websnag.elopenmike.com.core.data.KeystoreKeyAvailabilityProbe
import websnag.elopenmike.com.core.nfc.NfcManager
import websnag.elopenmike.com.service.WebSnagAccessibilityService

/**
 * Pure, Android-independent snapshot of the platform-derived diagnostics signals that
 * [DiagnosticsRepository] cannot obtain from committed repositories/flows (schedules, active
 * profile, persisted metadata). Every field is a primitive/enum so JVM unit tests can supply an
 * arbitrary snapshot via a fake [DiagnosticsStateSource] without touching any Android framework
 * class. [nfcHardwareEnabled], [accessibilityServiceEnabled] and [accessibilityServiceRunning]
 * only carry meaning when their corresponding capability actually exists; a source that reports
 * hardware/service absence should also report the paired "enabled" flag as `false`.
 */
data class DiagnosticsPlatformSnapshot(
    val appVersionName: String,
    val appVersionCode: Long,
    val buildType: BuildTypeCategory,
    val androidApiLevel: Int,
    val manufacturer: String,
    val model: String,
    val nfcHardwarePresent: Boolean,
    val nfcHardwareEnabled: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val accessibilityServiceRunning: Boolean,
    /** Raw current grant state; callers combine this with [androidApiLevel] to derive [PermissionState]. */
    val notificationPermissionGranted: Boolean,
    val exactAlarmAvailable: Boolean,
    val batteryOptimizationIgnored: Boolean,
    /** See [KeystoreKeyAvailabilityProbe]: whether the NFC HMAC key exists, never whether it was just created. */
    val keystoreKeyAvailable: Boolean
)

/**
 * Boundary between [DiagnosticsRepository] and the Android platform. Implementations must be
 * synchronous, side-effect free, and must never create/rotate the NFC HMAC Keystore key merely to
 * answer [snapshot] -- see [KeystoreKeyAvailabilityProbe].
 */
interface DiagnosticsStateSource {
    fun snapshot(): DiagnosticsPlatformSnapshot
}

/**
 * Reads [DiagnosticsPlatformSnapshot] straight from Android APIs. Never requests a permission,
 * creates a Keystore key, or performs network I/O; every read is a local, already-granted-or-not
 * capability check.
 */
class AndroidDiagnosticsStateSource(
    private val context: Context,
    private val keystoreProbe: KeystoreKeyAvailabilityProbe = AndroidKeystoreTagIdentityProtector()
) : DiagnosticsStateSource {

    private val nfcManager = NfcManager(context)

    @SuppressLint("InlinedApi")
    override fun snapshot(): DiagnosticsPlatformSnapshot {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        return DiagnosticsPlatformSnapshot(
            appVersionName = packageInfo.versionName.orEmpty(),
            appVersionCode = versionCode,
            buildType = if (isDebuggable) BuildTypeCategory.DEBUG else BuildTypeCategory.RELEASE,
            androidApiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            nfcHardwarePresent = nfcManager.isNfcSupported,
            nfcHardwareEnabled = nfcManager.isNfcSupported && nfcManager.isNfcEnabled,
            accessibilityServiceEnabled = isAccessibilityServiceEnabledInSecureSettings(),
            accessibilityServiceRunning = WebSnagAccessibilityService.isServiceRunning,
            notificationPermissionGranted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            exactAlarmAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            batteryOptimizationIgnored = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            keystoreKeyAvailable = keystoreProbe.isKeyAvailable()
        )
    }

    /**
     * Whether the WebSnag accessibility service is enabled in Settings.Secure, independent of
     * whether it is actually [running][WebSnagAccessibilityService.isServiceRunning]. Requires
     * both the global accessibility toggle and this service's component to be present in the
     * colon-separated enabled-services list; a hostile/malformed list value simply fails to match
     * rather than throwing.
     */
    private fun isAccessibilityServiceEnabledInSecureSettings(): Boolean {
        val globalToggleOn = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!globalToggleOn) return false
        val expectedComponent = ComponentName(context, WebSnagAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }
}
