package websnag.elopenmike.com.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import websnag.elopenmike.com.R
import websnag.elopenmike.com.core.model.EnforcementState
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.ui.common.FocusSessionTimer
import websnag.elopenmike.com.ui.theme.DarkBackground
import websnag.elopenmike.com.ui.theme.DarkSurface
import websnag.elopenmike.com.ui.theme.EmeraldSuccess
import websnag.elopenmike.com.ui.theme.IndigoLight
import websnag.elopenmike.com.ui.theme.IndigoPrimary
import websnag.elopenmike.com.ui.theme.RoseBlock
import websnag.elopenmike.com.ui.theme.Slate400
import websnag.elopenmike.com.ui.theme.Slate50
import websnag.elopenmike.com.ui.theme.Slate900

@Composable
fun BlockOverlayScreen(
    blockedPackageName: String,
    enforcementState: EnforcementState,
    onGoHomeClicked: () -> Unit,
    onStartEmergencyUnlock: (Boolean) -> Unit,
    onCancelEmergencyUnlock: () -> Unit
) {
    var showEmergencyDialog by remember(enforcementState.activeProfile?.id, enforcementState.sessionUiKey) {
        mutableStateOf(false)
    }
    val condition = enforcementState.activeProfile?.unlockCondition as? UnlockCondition.RequireNfcTag

    val infiniteTransition = rememberInfiniteTransition(label = "overlayPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Main Visual & Intention Card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.websnag_logo_circle),
                    contentDescription = "WebSnag",
                    modifier = Modifier
                        .size(90.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = when {
                        enforcementState.recoveryLockdownInForce -> "Saved Data Not Loaded"
                        enforcementState.filterMode == FilterMode.ALLOWLIST -> "App Not Allowed"
                        else -> "Distraction Paused"
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Slate50
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when {
                        // No profile is loaded, so no unlock policy can be evaluated. Blocking stays
                        // on for everything except emergency calling, the dialer, home and WebSnag.
                        enforcementState.recoveryLockdownInForce ->
                            "WebSnag could not load your saved settings, so it is pausing every app " +
                                "instead of unlocking. Your data was left untouched. Open WebSnag to try again."
                        enforcementState.filterMode == FilterMode.ALLOWLIST ->
                            "This app is not on your permitted essentials list for ${enforcementState.activeProfile?.name ?: "Focus Mode"}."
                        else -> "You decided that this app shouldn't be available right now."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Slate400,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Live Focus Duration Counter. Hidden while the lockdown is in force: ending a
                // session needs a write, which cannot happen while the store is unreadable, so
                // showing a running session next to the failure copy would promise an unlock that
                // no affordance on this screen can deliver.
                if (!enforcementState.recoveryLockdownInForce && enforcementState.sessionStartedAtEpochMs != null) {
                    FocusSessionTimer(
                        sessionStartedAtEpochMs = enforcementState.sessionStartedAtEpochMs,
                        isLarge = true,
                        textColor = IndigoLight
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Profile and App Details Card
                if (!enforcementState.recoveryLockdownInForce) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (enforcementState.filterMode == FilterMode.ALLOWLIST) "Active Profile (Allowlist Mode)" else "Active Profile (Blocklist)",
                                style = MaterialTheme.typography.labelSmall,
                                color = IndigoLight
                            )
                            Text(
                                text = enforcementState.activeProfile?.name ?: "Focus Mode",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Slate50
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "App: $blockedPackageName",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Slate400
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (!enforcementState.storageRecoveryRequired) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(IndigoPrimary.copy(alpha = 0.15f))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Nfc,
                            contentDescription = null,
                            tint = IndigoLight,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Tap physical NFC tag to unlock",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Slate50
                        )
                    }
                }
            }

            // Bottom Actions
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onGoHomeClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Return to Home Screen", style = MaterialTheme.typography.titleMedium)
                }

                if (!enforcementState.storageRecoveryRequired && condition?.allowEmergencyUnlock == true) {
                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = { showEmergencyDialog = true }
                    ) {
                        Text(
                            text = "Emergency Recovery (Intentional Friction)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }
                }
            }
        }
    }

    if (showEmergencyDialog && condition?.allowEmergencyUnlock == true) {
        EmergencyUnlockDialog(
            enforcementState = enforcementState,
            onDismiss = { showEmergencyDialog = false },
            onStartCooldown = onStartEmergencyUnlock,
            onCancelCooldown = onCancelEmergencyUnlock
        )
    }
}

@Composable
fun EmergencyUnlockDialog(
    enforcementState: EnforcementState,
    onDismiss: () -> Unit,
    onStartCooldown: (Boolean) -> Unit,
    onCancelCooldown: () -> Unit
) {
    val condition = enforcementState.activeProfile?.unlockCondition as? UnlockCondition.RequireNfcTag ?: return
    var intentionText by remember(
        enforcementState.activeProfile.id, enforcementState.sessionUiKey, enforcementState.emergencyRecovery?.requestId
    ) {
        mutableStateOf("")
    }
    val requiredPhrase = "I choose to pause my focus"
    val phraseConfirmed = intentionText.trim().equals(requiredPhrase, ignoreCase = true)
    val available = !enforcementState.storageRecoveryRequired && condition.allowEmergencyUnlock

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Emergency Unlock")
        },
        text = {
            Column {
                Text(
                    text = "WebSnag uses deliberate friction to prevent impulsive bypasses without completely locking you out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate400
                )

                Spacer(modifier = Modifier.height(16.dp))
                enforcementState.emergencyRecoveryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (enforcementState.storageRecoveryRequired) {
                    Text("Saved data is unavailable. Repair storage before changing recovery.")
                }

                if (enforcementState.emergencyCooldownActive) {
                    val secondsRemaining = (enforcementState.remainingEmergencyMs / 1000) +
                        if (enforcementState.remainingEmergencyMs % 1000 > 0) 1 else 0
                    val minutes = secondsRemaining / 60
                    val seconds = secondsRemaining % 60

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            style = MaterialTheme.typography.headlineLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = IndigoLight
                        )
                        Text(
                            text = when {
                                secondsRemaining != 0L -> "Cooldown in progress. Profile unlocks after the full wait is saved."
                                enforcementState.emergencyRecoveryError != null ->
                                    "Your completed cooldown is kept. Retrying the unlock automatically."
                                else -> "Saving unlock…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = if (condition.requireIntentionPhrase)
                            "To start a ${condition.emergencyCooldownMinutes}-minute emergency unlock timer, type the phrase below:"
                        else "Start a ${condition.emergencyCooldownMinutes}-minute emergency unlock timer. No intention phrase is required.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )

                    if (condition.requireIntentionPhrase) {
                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "\"$requiredPhrase\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = IndigoLight
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = intentionText,
                            onValueChange = { intentionText = it },
                            label = { Text("Intention phrase") },
                            placeholder = { Text("Type the phrase exactly") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (enforcementState.emergencyRecovery != null) {
                OutlinedButton(onClick = {
                    onCancelCooldown()
                }, enabled = available) {
                    Text("Cancel Timer")
                }
            } else {
                Button(
                    onClick = { onStartCooldown(condition.requireIntentionPhrase && phraseConfirmed) },
                    enabled = available && condition.emergencyCooldownMinutes > 0 &&
                        (!condition.requireIntentionPhrase || phraseConfirmed)
                ) {
                    Text("Start ${condition.emergencyCooldownMinutes} Min Cooldown")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
