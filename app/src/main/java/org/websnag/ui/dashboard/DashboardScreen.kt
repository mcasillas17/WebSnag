package org.websnag.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.websnag.core.model.FilterMode
import org.websnag.core.model.Profile
import org.websnag.ui.common.FocusSessionTimer
import org.websnag.ui.dashboard.components.HoldToLockCard
import org.websnag.ui.theme.EmeraldSuccess
import org.websnag.ui.theme.IndigoPrimary
import org.websnag.ui.theme.RoseBlock
import org.websnag.ui.theme.Slate400
import org.websnag.ui.theme.Slate700
import org.websnag.ui.theme.Slate900

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
    val uiState by viewModel.uiState.collectAsState()
    val isAccessibilityActive = viewModel.isAccessibilityServiceRunning()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            HeaderSection()
        }

        // Accessibility warning banner if not running
        if (!isAccessibilityActive) {
            item {
                AccessibilityBanner(onNavigateToSetup = onNavigateToSetup)
            }
        }

        // Status Card
        item {
            EnforcementStatusCard(
                isBlockingActive = enforcementState.isBlockingActive,
                activeProfile = enforcementState.activeProfile,
                sessionStartedAtEpochMs = enforcementState.sessionStartedAtEpochMs,
                blockedCount = enforcementState.blockedPackages.size
            )
        }

        // Interactive "Hold to Lock" gesture when idle
        if (!enforcementState.isBlockingActive && profiles.isNotEmpty()) {
            item {
                HoldToLockCard(
                    profile = profiles.firstOrNull(),
                    onLockTriggered = { profileToLock ->
                        viewModel.quickLockProfile(profileToLock)
                    }
                )
            }
        }

        // Quick NFC Indicator
        item {
            NfcScanHelperCard(
                isBlockingActive = enforcementState.isBlockingActive,
                enrolledTagCount = tags.size,
                onEnrollClicked = onNavigateToEnrollTag
            )
        }

        // Blocking Profiles Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Blocking Profiles",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = onNavigateToProfiles) {
                    Text("Manage All")
                }
            }
        }

        if (profiles.isEmpty()) {
            item {
                EmptyProfilesCard(onCreateProfile = { onNavigateToProfileEditor(null) })
            }
        } else {
            items(profiles) { profile ->
                ProfileDashboardItem(
                    profile = profile,
                    onToggle = { viewModel.onProfileToggleClicked(profile) },
                    onEdit = { onNavigateToProfileEditor(profile.id) }
                )
            }
        }

        // NFC Tags Quick Hub
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enrolled NFC Tags",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = onNavigateToTags) {
                    Text("View (${tags.size})")
                }
            }
        }

        item {
            NfcTagsSummaryCard(
                tagsCount = tags.size,
                onEnrollTag = onNavigateToEnrollTag,
                onViewTags = onNavigateToTags
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
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
                    tint = IndigoPrimary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("Physical NFC Tag Required")
            },
            text = {
                Text(
                    "Profile '${profile.name}' is currently active and locked. To deactivate it and restore access to blocked apps, tap your physical NFC tag against the back of your phone."
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
private fun HeaderSection() {
    Column {
        Text(
            text = "WebSnag",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Environmental self-control system",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccessibilityBanner(onNavigateToSetup: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToSetup() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Accessibility Service Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Enable WebSnag in Accessibility to enforce distraction blocking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EnforcementStatusCard(
    isBlockingActive: Boolean,
    activeProfile: Profile?,
    sessionStartedAtEpochMs: Long?,
    blockedCount: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isBlockingActive) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .scale(if (isBlockingActive) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(
                        if (isBlockingActive) RoseBlock.copy(alpha = 0.2f) else EmeraldSuccess.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isBlockingActive) Icons.Default.Lock else Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (isBlockingActive) RoseBlock else EmeraldSuccess,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isBlockingActive) "Focus Session Active" else "Ready to Focus",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Live Focus Duration Timer when active
            if (isBlockingActive && sessionStartedAtEpochMs != null) {
                FocusSessionTimer(
                    sessionStartedAtEpochMs = sessionStartedAtEpochMs,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Text(
                text = if (isBlockingActive) {
                    val modeDesc = if (activeProfile?.filterMode == FilterMode.ALLOWLIST) {
                        "Allowlist Mode • ${activeProfile.blockedPackages.size} essentials permitted"
                    } else {
                        "Blocklist Mode • $blockedCount apps blocked"
                    }
                    "Profile '${activeProfile?.name}' • $modeDesc"
                } else {
                    "Tap an NFC tag, hold to lock, or activate a profile below to begin."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NfcScanHelperCard(
    isBlockingActive: Boolean,
    enrolledTagCount: Int,
    onEnrollClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NFC Tap Ready",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isBlockingActive) "Tap your NFC tag to unlock" else "Tap an enrolled tag to activate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (enrolledTagCount == 0) {
                OutlinedButton(onClick = onEnrollClicked) {
                    Text("Enroll Tag")
                }
            }
        }
    }
}

@Composable
private fun ProfileDashboardItem(
    profile: Profile,
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (profile.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (profile.isActive) Icons.Default.CheckCircle else Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (profile.isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val filterDesc = if (profile.filterMode == FilterMode.ALLOWLIST) {
                    "Allowlist: ${profile.blockedPackages.size} permitted"
                } else {
                    "${profile.blockedPackages.size} apps blocked"
                }
                Text(
                    text = "$filterDesc${if (profile.linkedTagUid != null) " • Linked to tag" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (profile.isActive) RoseBlock else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = if (profile.isActive) "Unlock" else "Activate")
            }
        }
    }
}

@Composable
private fun EmptyProfilesCard(onCreateProfile: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No Profiles Yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Create your first blocking profile to choose which apps to pause.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onCreateProfile) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Create Profile")
            }
        }
    }
}

@Composable
private fun NfcTagsSummaryCard(
    tagsCount: Int,
    onEnrollTag: () -> Unit,
    onViewTags: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "$tagsCount NFC Tag${if (tagsCount == 1) "" else "s"} Enrolled",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Universal support for any NFC tag or card",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(onClick = onEnrollTag) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Enroll")
            }
        }
    }
}
