package websnag.elopenmike.com

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import websnag.elopenmike.com.service.WebSnagAccessibilityService

@RunWith(AndroidJUnit4::class)
class PrivacyManifestTest {

    @Test
    fun declaresNoInternetAndCannotRetrieveAccessibilityWindowContent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val permissions = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()
        val component = ComponentName(context, WebSnagAccessibilityService::class.java)
        @Suppress("DEPRECATION")
        val serviceInfo = packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        val parser = serviceInfo.loadXmlMetaData(packageManager, AccessibilityService.SERVICE_META_DATA)

        assertFalse("android.permission.INTERNET" in permissions)
        parser.use {
            while (it.next() != XmlPullParser.START_TAG) {
                // Advance to the service declaration.
            }
            assertFalse(
                it.getAttributeBooleanValue(
                    "http://schemas.android.com/apk/res/android",
                    "canRetrieveWindowContent",
                    true
                )
            )
        }
    }
}
