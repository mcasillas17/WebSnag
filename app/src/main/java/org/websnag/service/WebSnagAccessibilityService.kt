package org.websnag.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import org.websnag.core.enforcement.EnforcementEngine
import org.websnag.ui.overlay.BlockOverlayActivity

/**
 * Low-latency, battery-efficient Accessibility Service for intercepting blocked foreground applications.
 */
class WebSnagAccessibilityService : AccessibilityService() {

    private var lastInterceptedPackage: String? = null
    private var lastInterceptedTimeMs: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Guard against intercepting self, system UI, or launcher
        if (isExemptPackage(packageName)) {
            return
        }

        val engine = EnforcementEngine.get() ?: return
        if (engine.isPackageBlocked(packageName)) {
            val now = System.currentTimeMillis()
            // Debounce rapid window changes for same package within 800ms
            if (packageName == lastInterceptedPackage && (now - lastInterceptedTimeMs) < 800) {
                return
            }

            lastInterceptedPackage = packageName
            lastInterceptedTimeMs = now

            engine.recordBlockedAttempt(packageName)

            // Intercept: Return home and launch blocker overlay
            performGlobalAction(GLOBAL_ACTION_HOME)

            val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, packageName)
            }
            startActivity(overlayIntent)
        }
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    private fun isExemptPackage(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        if (packageName == "com.android.systemui") return true
        if (packageName == "android") return true

        // Check if package is default launcher/home
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }

        val launcherPackage = resolveInfo?.activityInfo?.packageName
        if (launcherPackage != null && launcherPackage == packageName) {
            return true
        }

        return false
    }

    companion object {
        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
    }

    override fun onDestroy() {
        isServiceRunning = false
        super.onDestroy()
    }
}
