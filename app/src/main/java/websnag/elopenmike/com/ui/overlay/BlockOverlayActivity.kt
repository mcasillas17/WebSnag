package websnag.elopenmike.com.ui.overlay

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import websnag.elopenmike.com.WebSnagApp
import websnag.elopenmike.com.core.nfc.NfcTagAction
import websnag.elopenmike.com.core.enforcement.EndRequest
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
                handleOverlayNfcAction(
                    action = action,
                    unlock = { requested ->
                        val enrolled = app.nfcTagRepository.getTagForUid(requested.tagUid)
                        enrolled != null && app.enforcementEngine.requestEnd(
                            requested.profile.id, EndRequest.Nfc(enrolled.id, isEnrolled = true)
                        )
                    },
                    onUnlocked = ::finish,
                    onMessage = { Toast.makeText(this@BlockOverlayActivity, it, Toast.LENGTH_LONG).show() }
                )
            }

        }

        // Finish if blocking is deactivated externally. An unreadable-storage interception is not
        // an active session but is still an intentional block, so it must keep its explanation.
        lifecycleScope.launch {
            app.enforcementEngine.enforcementState.collectLatest {
                val state = app.enforcementEngine.enforcementState.value
                if (!state.isBlockingActive && !state.recoveryLockdownInForce) {
                    finish()
                }
            }
        }

        setContent {
            // Collected, not snapshotted: this activity now stays open across a recovery/active
            // transition, so its copy has to follow the state instead of freezing at composition.
            val enforcementState by app.enforcementEngine.enforcementState.collectAsState()
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
                    onStartEmergencyUnlock = { confirmed ->
                        lifecycleScope.launch {
                            app.enforcementEngine.startEmergencyUnlock(
                                confirmed, enforcementState.activeProfile?.sessionId,
                                enforcementState.emergencyRecovery?.requestId
                            )
                        }
                    },
                    onCancelEmergencyUnlock = {
                        enforcementState.emergencyRecovery?.requestId?.let { requestId ->
                            lifecycleScope.launch { app.enforcementEngine.cancelEmergencyUnlock(requestId) }
                        }
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

internal suspend fun handleOverlayNfcAction(
    action: NfcTagAction,
    unlock: suspend (NfcTagAction.DeactivateProfile) -> Boolean,
    onUnlocked: () -> Unit,
    onMessage: (String) -> Unit
) {
    when (action) {
        NfcTagAction.StorageUnavailable -> onMessage("Saved data has not loaded yet, so this tag was ignored.")
        is NfcTagAction.DeactivateProfile -> if (unlock(action)) onUnlocked()
        else -> Unit
    }
}
