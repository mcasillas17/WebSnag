package websnag.elopenmike.com.core.enforcement

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Only emergency friction uses this clock. elapsedRealtime includes device sleep. */
class EmergencyClock(
    val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    val bootId: () -> String? = { null }
) {
    companion object {
        fun android(context: Context): EmergencyClock = EmergencyClock(bootId = {
            // BOOT_COUNT is maintained by the system, not derived from mutable wall time.
            // If it cannot be read, restoration deliberately starts a full new cooldown.
            runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
                    .takeIf { it >= 0 }?.let { "boot:$it" }
            }.getOrNull()
        })
    }
}
