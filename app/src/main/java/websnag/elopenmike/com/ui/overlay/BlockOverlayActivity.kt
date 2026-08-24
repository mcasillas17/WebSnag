package websnag.elopenmike.com.ui.overlay

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import websnag.elopenmike.com.WebSnagApp
import websnag.elopenmike.com.core.nfc.NfcTagAction
import websnag.elopenmike.com.ui.theme.WebSnagTheme

class BlockOverlayActivity : ComponentActivity() {

    private lateinit var app: WebSnagApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as WebSnagApp

        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: "distracting application"

        // Observe NFC taps on overlay screen
        lifecycleScope.launch {
            app.nfcManager.scannedTagFlow.collectLatest { scanned ->
                val action = app.nfcActionResolver.resolve(scanned.uidHex, scanned.customPayload)
                if (action is NfcTagAction.DeactivateProfile) {
                    app.enforcementEngine.deactivateProfile(action.profile.id)
                    finish()
                }
            }
        }

        // Finish if blocking is deactivated externally
        lifecycleScope.launch {
            app.enforcementEngine.enforcementState.collectLatest { state ->
                if (!state.isBlockingActive) {
                    finish()
                }
            }
        }

        setContent {
            val enforcementState = app.enforcementEngine.enforcementState.value
            WebSnagTheme(darkTheme = true) {
                BlockOverlayScreen(
                    blockedPackageName = blockedPackage,
                    enforcementState = enforcementState,
                    onGoHomeClicked = {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                        finish()
                    },
                    onStartEmergencyUnlock = { minutes ->
                        app.enforcementEngine.startEmergencyUnlock(minutes) {
                            finish()
                        }
                    },
                    onCancelEmergencyUnlock = {
                        app.enforcementEngine.cancelEmergencyUnlock()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.nfcManager.enableReaderMode(this)
    }

    override fun onPause() {
        super.onPause()
        app.nfcManager.disableReaderMode(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
    }
}
