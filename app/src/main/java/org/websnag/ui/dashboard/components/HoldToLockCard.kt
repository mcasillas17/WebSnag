package org.websnag.ui.dashboard.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.websnag.core.model.FilterMode
import org.websnag.core.model.Profile
import org.websnag.ui.theme.IndigoPrimary
import org.websnag.ui.theme.RoseBlock

/**
 * Interactive "Hold to Lock" widget inspired by physical-friction self-control.
 * Allows users to initiate focus sessions on-the-go without needing their tag nearby,
 * knowing that deactivating the session will require their physical NFC tag.
 */
@Composable
fun HoldToLockCard(
    profile: Profile?,
    onLockTriggered: (Profile) -> Unit,
    modifier: Modifier = Modifier
) {
    if (profile == null) return

    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var isPressed by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(IndigoPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = IndigoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Quick Lock: ${profile.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (profile.filterMode == FilterMode.ALLOWLIST)
                                "Allowlist: ${profile.blockedPackages.size} essentials permitted"
                            else
                                "Blocklist: ${profile.blockedPackages.size} distracting apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Interactive Press & Hold Button
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPressed) IndigoPrimary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surface
                    )
                    .pointerInput(profile) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                                holdJob = coroutineScope.launch {
                                    val hapticJob = launch {
                                        delay(350)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        delay(350)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        delay(350)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }

                                    progress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(durationMillis = 1500, easing = LinearEasing)
                                    )

                                    hapticJob.cancel()
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isPressed = false
                                    progress.snapTo(0f)
                                    onLockTriggered(profile)
                                }

                                val released = tryAwaitRelease()
                                if (progress.value < 0.98f) {
                                    isPressed = false
                                    holdJob?.cancel()
                                    coroutineScope.launch {
                                        progress.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                        )
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // Background track
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.size(126.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    strokeWidth = 6.dp
                )

                // Active progress sweep
                CircularProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier.size(126.dp),
                    color = IndigoPrimary,
                    strokeWidth = 6.dp
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Hold to Lock",
                        tint = if (isPressed) IndigoPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(32.dp)
                            .scale(if (isPressed) 1.1f else 1f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isPressed) "HOLD..." else "HOLD TO LOCK",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = if (isPressed) IndigoPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Press and hold for 1.5s to start focus session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
