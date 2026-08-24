package org.websnag

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.websnag.core.nfc.NfcPayloadHelper
import org.websnag.core.nfc.NfcTagAction
import org.websnag.ui.activity.ActivityScreen
import org.websnag.ui.activity.ActivityViewModel
import org.websnag.ui.dashboard.DashboardScreen
import org.websnag.ui.dashboard.DashboardViewModel
import org.websnag.ui.navigation.Screen
import org.websnag.ui.profiles.ProfileEditorScreen
import org.websnag.ui.profiles.ProfilesScreen
import org.websnag.ui.profiles.ProfilesViewModel
import org.websnag.ui.setup.PermissionsScreen
import org.websnag.ui.tags.EnrollTagScreen
import org.websnag.ui.tags.TagsScreen
import org.websnag.ui.tags.TagsViewModel
import org.websnag.ui.theme.WebSnagTheme

class MainActivity : ComponentActivity() {

    private lateinit var app: WebSnagApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as WebSnagApp

        // Process any incoming NFC intent on cold start
        handleNfcIntent(intent)

        // Observe foreground NFC scans
        lifecycleScope.launch {
            app.nfcManager.scannedTagFlow.collectLatest { scanned ->
                handleScannedTag(scanned.uidHex, scanned.customPayload)
            }
        }

        setContent {
            val themeMode by app.localDataStore.themeModeFlow.collectAsState(initial = org.websnag.core.model.AppThemeMode.SYSTEM)
            WebSnagTheme(themeMode = themeMode) {
                MainAppContent(
                    app = app,
                    currentThemeMode = themeMode,
                    onThemeModeSelected = { newMode ->
                        lifecycleScope.launch {
                            app.localDataStore.setThemeMode(newMode)
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        app.nfcManager.enableReaderMode(this)
    }

    override fun onPause() {
        super.onPause()
        app.nfcManager.disableReaderMode(this)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED
        ) {
            val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            if (tag != null) {
                app.nfcManager.handleTagDiscovered(tag)
            }
        }
    }

    private fun handleScannedTag(uidHex: String, payload: String?) {
        lifecycleScope.launch {
            val action = app.nfcActionResolver.resolve(uidHex, payload)
            when (action) {
                is NfcTagAction.ActivateProfile -> {
                    app.enforcementEngine.activateProfile(action.profile.id)
                    Toast.makeText(this@MainActivity, "Locked with: ${action.profile.name}", Toast.LENGTH_SHORT).show()
                }
                is NfcTagAction.DeactivateProfile -> {
                    app.enforcementEngine.deactivateProfile(action.profile.id)
                    Toast.makeText(this@MainActivity, "Unlocked: ${action.profile.name}", Toast.LENGTH_SHORT).show()
                }
                is NfcTagAction.UnlockRejected -> {
                    Toast.makeText(this@MainActivity, "Wrong NFC tag for active profile", Toast.LENGTH_LONG).show()
                }
                is NfcTagAction.EnrolledTagDetected -> {
                    Toast.makeText(this@MainActivity, "Tapped: ${action.tagRecord.label}", Toast.LENGTH_SHORT).show()
                }
                is NfcTagAction.UnknownTagDetected -> {
                    // Handled if currently on Enrollment screen via SharedFlow
                }
            }
        }
    }
}

@Composable
fun MainAppContent(
    app: WebSnagApp,
    currentThemeMode: org.websnag.core.model.AppThemeMode = org.websnag.core.model.AppThemeMode.SYSTEM,
    onThemeModeSelected: (org.websnag.core.model.AppThemeMode) -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val dashboardViewModel = remember {
        DashboardViewModel(app.profileRepository, app.nfcTagRepository, app.localDataStore, app.enforcementEngine)
    }
    val activityViewModel = remember {
        ActivityViewModel(app.localDataStore, app.enforcementEngine)
    }
    val profilesViewModel = remember {
        ProfilesViewModel(app.profileRepository, app.nfcTagRepository, app.installedAppsRepository, app.enforcementEngine)
    }
    val tagsViewModel = remember {
        TagsViewModel(app.nfcTagRepository, app.profileRepository, app.nfcManager)
    }

    val showBottomBar = currentRoute in listOf(
        Screen.Dashboard.route,
        Screen.Activity.route,
        Screen.Tags.route,
        Screen.Setup.route
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WebSnagBottomNavItem(
                            label = "WebSnag",
                            icon = Icons.Default.Shield,
                            isSelected = currentRoute == Screen.Dashboard.route,
                            onClick = {
                                if (currentRoute != Screen.Dashboard.route) {
                                    navController.navigate(Screen.Dashboard.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )

                        WebSnagBottomNavItem(
                            label = "Activity",
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            isSelected = currentRoute == Screen.Activity.route,
                            onClick = {
                                if (currentRoute != Screen.Activity.route) {
                                    navController.navigate(Screen.Activity.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )

                        WebSnagBottomNavItem(
                            label = "NFC Hub",
                            icon = Icons.Default.Nfc,
                            isSelected = currentRoute == Screen.Tags.route,
                            onClick = {
                                if (currentRoute != Screen.Tags.route) {
                                    navController.navigate(Screen.Tags.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )

                        WebSnagBottomNavItem(
                            label = "Settings",
                            icon = Icons.Default.Settings,
                            isSelected = currentRoute == Screen.Setup.route,
                            onClick = {
                                if (currentRoute != Screen.Setup.route) {
                                    navController.navigate(Screen.Setup.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Dashboard.route
            ) {
                composable(Screen.Dashboard.route) {
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        onNavigateToProfiles = { navController.navigate(Screen.Profiles.route) },
                        onNavigateToProfileEditor = { profileId ->
                            navController.navigate(Screen.ProfileEditor.createRoute(profileId))
                        },
                        onNavigateToTags = { navController.navigate(Screen.Tags.route) },
                        onNavigateToEnrollTag = { navController.navigate(Screen.EnrollTag.route) },
                        onNavigateToSetup = { navController.navigate(Screen.Setup.route) }
                    )
                }

                composable(Screen.Activity.route) {
                    ActivityScreen(
                        viewModel = activityViewModel
                    )
                }

                composable(Screen.Profiles.route) {
                    ProfilesScreen(
                        viewModel = profilesViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToEditor = { profileId ->
                            navController.navigate(Screen.ProfileEditor.createRoute(profileId))
                        }
                    )
                }

                composable(
                    route = Screen.ProfileEditor.route,
                    arguments = listOf(navArgument("profileId") {
                        type = NavType.StringType
                    })
                ) { backStackEntry ->
                    val profileId = backStackEntry.arguments?.getString("profileId")
                    ProfileEditorScreen(
                        profileId = if (profileId == "new") null else profileId,
                        viewModel = profilesViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Tags.route) {
                    TagsScreen(
                        viewModel = tagsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToEnroll = { navController.navigate(Screen.EnrollTag.route) }
                    )
                }

                composable(Screen.EnrollTag.route) {
                    EnrollTagScreen(
                        viewModel = tagsViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Setup.route) {
                    PermissionsScreen(
                        currentThemeMode = currentThemeMode,
                        onThemeModeSelected = onThemeModeSelected,
                        onNavigateToProfiles = { navController.navigate(Screen.Profiles.route) },
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

@Composable
private fun WebSnagBottomNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(3.dp))
        // Active indicator dot beneath label (Brick style)
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
        )
    }
}
