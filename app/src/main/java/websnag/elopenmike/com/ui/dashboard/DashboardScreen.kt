package websnag.elopenmike.com.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import websnag.elopenmike.com.R
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.Profile
import websnag.elopenmike.com.core.model.UnlockCondition
import websnag.elopenmike.com.ui.common.FocusSessionTimer
import websnag.elopenmike.com.ui.theme.EmeraldSuccess
import websnag.elopenmike.com.ui.theme.RoseBlock

/**
 * Zen Minimalist Dashboard for WebSnag.
 * Eliminates visual clutter and focuses 100% on the central focus state.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToProfiles: () -> Unit,
    onNavigateToProfileEditor: (String?) -> Unit,
    onNavigateToTags: () -> Unit,
    onNavigateToEnrollTag: () -> Unit,
    onNavigateToSetup: () -> Unit
) {
    val enforcementState by viewModel.enforcementState.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val selectedProfileId by viewModel.selectedProfileId.collectAsState()
    val todayFocusMinutes by viewModel.todayFocusMinutes.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isAccessibilityActive = viewModel.isAccessibilityServiceRunning()

    // Determine current selected profile
    val currentProfile = profiles.firstOrNull { it.id == selectedProfileId }
        ?: enforcementState.activeProfile
        ?: profiles.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header Row with Focus Pill
        TopHeaderBar(
            todayFocusMinutes = todayFocusMinutes,
            isAccessibilityActive = isAccessibilityActive,
            onNavigateToSetup = onNavigateToSetup
        )

        // Center Hero Focus Stage
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (enforcementState.isBlockingActive) {
                ActiveFocusHeroView(
                    activeProfile = enforcementState.activeProfile,
                    sessionStartedAtEpochMs = enforcementState.sessionStartedAtEpochMs,
                    onUnlockRequested = {
                        viewModel.emergencyUnlockActiveProfile()
                    }
                )
            } else {
                IdleFocusHeroView(
                    selectedProfile = currentProfile,
                    allProfiles = profiles,
                    onProfileSelected = { viewModel.selectProfile(it.id) },
                    onCreateProfileClicked = { onNavigateToProfileEditor(null) },
                    onLockTriggered = { profileToLock ->
                        viewModel.quickLockProfile(profileToLock, tags)
                    }
                )
            }
        }

        // Bottom Ambient Status Pill
        BottomStatusPill(
            isBlockingActive = enforcementState.isBlockingActive,
            tagsCount = tags.size,
            onNavigateToTags = onNavigateToTags,
            onNavigateToEnrollTag = onNavigateToEnrollTag
        )
    }

    // Modal when user tries to lock without an enrolled NFC tag
    if (uiState.showNoNfcEnrolledWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNoNfcWarning() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("No NFC Tags Enrolled")
            },
            text = {
                Text(
                    "This profile requires a physical NFC tag to unlock. You haven't registered any NFC tags yet.\n\nPlease enroll an NFC tag first in the NFC Hub to avoid locking yourself out without a key!"
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.dismissNoNfcWarning()
                    onNavigateToEnrollTag()
                }) {
                    Text("Enroll NFC Tag")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNoNfcWarning() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal when user tries to manually deactivate an NFC-locked profile
    if (uiState.nfcUnlockPromptProfile != null) {
        val profile = uiState.nfcUnlockPromptProfile!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissNfcPrompt() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("Physical NFC Tag Required")
            },
            text = {
                Text(
                    "Profile '${profile.name}' is currently active. To unlock and restore access to blocked apps, tap your physical NFC tag against the back of your phone."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissNfcPrompt() }) {
                    Text("Understood")
                }
            }
        )
    }
}

@Composable
private fun TopHeaderBar(
    todayFocusMinutes: Int,
    isAccessibilityActive: Boolean,
    onNavigateToSetup: () -> Unit
) {
    val hours = todayFocusMinutes / 60
    val mins = todayFocusMinutes % 60
    val timeTodayText = if (hours > 0) "${hours}h ${mins}m today" else "${mins}m today"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Web",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Snag",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Brick-style Top Metric Pill (0h 0m today)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeTodayText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Subtle Accessibility Pill if setup needed
            if (!isAccessibilityActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                            RoundedCornerShape(20.dp)
                        )
                        .clickable { onNavigateToSetup() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Idle State: Minimalist Profile Switcher + Hero Hold-to-Lock Button
 */
@Composable
private fun IdleFocusHeroView(
    selectedProfile: Profile?,
    allProfiles: List<Profile>,
    onProfileSelected: (Profile) -> Unit,
    onCreateProfileClicked: () -> Unit,
    onLockTriggered: (Profile) -> Unit
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Profile Selector Pill
        if (selectedProfile != null) {
            Box {
                Surface(
                    onClick = { isDropdownExpanded = true },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = selectedProfile.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Switch Profile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false }
                ) {
                    allProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = profile.name,
                                        fontWeight = if (profile.id == selectedProfile.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = if (profile.filterMode == FilterMode.ALLOWLIST)
                                            "Allowlist • ${profile.blockedPackages.size} essentials"
                                        else
                                            "Blocklist • ${profile.blockedPackages.size} apps",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            trailingIcon = {
                                if (profile.id == selectedProfile.id) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                onProfileSelected(profile)
                                isDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle description of active strategy
            Text(
                text = if (selectedProfile.filterMode == FilterMode.ALLOWLIST)
                    "Allowlist: ${selectedProfile.blockedPackages.size} essential apps permitted"
                else
                    "Blocklist: ${selectedProfile.blockedPackages.size} distracting apps blocked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedButton(onClick = onCreateProfileClicked) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Create Focus Profile")
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Hero Hold to Lock Button
        if (selectedProfile != null) {
            HeroHoldToLockButton(
                profile = selectedProfile,
                onLockTriggered = onLockTriggered
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Hold 1.5s to start session\nor tap an enrolled NFC tag",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 200dp Zen Hero Hold-to-Lock Button with circular sweep & progressive haptic feedback
 */
@Composable
private fun HeroHoldToLockButton(
    profile: Profile,
    onLockTriggered: (Profile) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(CircleShape)
            .background(
                if (isPressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
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
        // Outer Background Track
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(194.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            strokeWidth = 7.dp
        )

        // Active Progress Sweep
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.size(194.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 7.dp
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPressed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Hold to Lock",
                    tint = if (isPressed) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .scale(if (isPressed) 1.15f else 1f)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (isPressed) "HOLDING..." else "HOLD TO LOCK",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp,
                color = if (isPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Active State: Ambient Breathing Focus Ring with prominent live duration timer
 */
@Composable
private fun ActiveFocusHeroView(
    activeProfile: Profile?,
    sessionStartedAtEpochMs: Long?,
    onUnlockRequested: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambientPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Active Profile Pill
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(EmeraldSuccess)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${activeProfile?.name ?: "Focus"} • Active",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Hero Live Timer Ring
        Box(
            modifier = Modifier
                .size(210.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    3.dp,
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (sessionStartedAtEpochMs != null) {
                    FocusSessionTimer(
                        sessionStartedAtEpochMs = sessionStartedAtEpochMs,
                        isLarge = true
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "FOCUS ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Action instructions
        Text(
            text = "Tap your physical NFC tag to unlock\nor tap below for emergency recovery",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        TextButton(
            onClick = onUnlockRequested,
            colors = ButtonDefaults.textButtonColors(contentColor = RoseBlock)
        ) {
            Text("Emergency Unlock", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Bottom Minimalist NFC & Status Pill
 */
@Composable
private fun BottomStatusPill(
    isBlockingActive: Boolean,
    tagsCount: Int,
    onNavigateToTags: () -> Unit,
    onNavigateToEnrollTag: () -> Unit
) {
    Surface(
        onClick = if (tagsCount == 0) onNavigateToEnrollTag else onNavigateToTags,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Nfc,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (tagsCount == 0) "No NFC tags enrolled • Tap to setup"
                else "$tagsCount NFC Tag${if (tagsCount == 1) "" else "s"} Enrolled • Tap tag anytime",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
