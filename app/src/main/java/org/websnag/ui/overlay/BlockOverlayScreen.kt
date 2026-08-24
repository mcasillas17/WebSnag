package org.websnag.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.websnag.core.model.EnforcementState
import org.websnag.ui.theme.DarkBackground
import org.websnag.ui.theme.DarkSurface
import org.websnag.ui.theme.EmeraldSuccess
import org.websnag.ui.theme.IndigoLight
import org.websnag.ui.theme.IndigoPrimary
import org.websnag.ui.theme.RoseBlock
import org.websnag.ui.theme.Slate400
import org.websnag.ui.theme.Slate50
import org.websnag.ui.theme.Slate900

@Composable
fun BlockOverlayScreen(
    blockedPackageName: String,
    enforcementState: EnforcementState,
    onGoHomeClicked: () -> Unit,
    onStartEmergencyUnlock: (Int) -> Unit,
    onCancelEmergencyUnlock: () -> Unit
) {
    var showEmergencyDialog by remember { mutableStateOf(false) }

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
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(RoseBlock.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = RoseBlock,
                        modifier = Modifier.size(50.dp)
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Distraction Paused",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Slate50
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "You decided that this app shouldn't be available right now.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Slate400,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Profile and App Details Card
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
                            text = "Active Profile",
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

                // NFC Tap Reminder
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

    if (showEmergencyDialog) {
        EmergencyUnlockDialog(
            enforcementState = enforcementState,
            onDismiss = { showEmergencyDialog = false },
            onStartCooldown = { onStartEmergencyUnlock(5) },
            onCancelCooldown = onCancelEmergencyUnlock
        )
    }
}

@Composable
private fun EmergencyUnlockDialog(
    enforcementState: EnforcementState,
    onDismiss: () -> Unit,
    onStartCooldown: () -> Unit,
    onCancelCooldown: () -> Unit
) {
    var intentionText by remember { mutableStateOf("") }
    val requiredPhrase = "I choose to pause my focus"

    var remainingMs by remember { mutableLongStateOf(enforcementState.remainingEmergencyMs) }

    LaunchedEffect(enforcementState.emergencyCooldownActive) {
        while (enforcementState.emergencyCooldownActive) {
            remainingMs = enforcementState.remainingEmergencyMs
            delay(1000)
        }
    }

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

                if (enforcementState.emergencyCooldownActive) {
                    val minutes = (remainingMs / 1000) / 60
                    val seconds = (remainingMs / 1000) % 60

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
                            text = "Cooldown in progress. Profile will unlock when timer expires.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = "To start a 5-minute emergency unlock timer, type the phrase below:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )

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
                        placeholder = { Text("Type the phrase exactly") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (enforcementState.emergencyCooldownActive) {
                OutlinedButton(onClick = {
                    onCancelCooldown()
                    onDismiss()
                }) {
                    Text("Cancel Timer")
                }
            } else {
                Button(
                    onClick = { onStartCooldown() },
                    enabled = intentionText.trim().equals(requiredPhrase, ignoreCase = true)
                ) {
                    Text("Start 5 Min Cooldown")
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
