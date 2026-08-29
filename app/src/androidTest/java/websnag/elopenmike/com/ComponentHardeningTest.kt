package websnag.elopenmike.com

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.ui.overlay.BlockOverlayActivity
import websnag.elopenmike.com.core.schedule.ScheduleAlarmReceiver

@RunWith(AndroidJUnit4::class)
class ComponentHardeningTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val packageManager = context.packageManager

    @Test
    fun overlayIsNotExportedAndAppDataIsNotBackupEligible() {
        @Suppress("DEPRECATION")
        val overlay = packageManager.getActivityInfo(
            ComponentName(context, BlockOverlayActivity::class.java),
            0
        )

        assertFalse(overlay.exported)
        assertFalse(
            context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        )
    }

    @Test
    fun appDoesNotClaimGenericNfcDispatch() {
        @Suppress("DEPRECATION")
        val activities = packageManager.queryIntentActivities(
            Intent(NfcAdapter.ACTION_TAG_DISCOVERED),
            PackageManager.MATCH_DEFAULT_ONLY
        )

        assertFalse(activities.any { it.activityInfo.packageName == context.packageName })
    }

    @Test
    fun scheduledAlarmReceiverIsNotExported() {
        @Suppress("DEPRECATION")
        val receiver = packageManager.getReceiverInfo(
            ComponentName(context, ScheduleAlarmReceiver::class.java),
            0
        )

        assertFalse(receiver.exported)
    }
}
