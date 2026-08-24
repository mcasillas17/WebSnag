package org.websnag

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Modifier
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
                    Toast.makeText(this@MainActivity, "Activated: ${action.profile.name}", Toast.LENGTH_SHORT).show()
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
        DashboardViewModel(app.profileRepository, app.nfcTagRepository, app.enforcementEngine)
    }
    val profilesViewModel = remember {
        ProfilesViewModel(app.profileRepository, app.nfcTagRepository, app.installedAppsRepository, app.enforcementEngine)
    }
    val tagsViewModel = remember {
        TagsViewModel(app.nfcTagRepository, app.profileRepository, app.nfcManager)
    }

    val showBottomBar = currentRoute in listOf(
        Screen.Dashboard.route,
        Screen.Profiles.route,
        Screen.Tags.route,
        Screen.Setup.route
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    NavigationBarItem(
                        selected = currentRoute == Screen.Dashboard.route,
                        onClick = {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                        label = { Text("Dashboard") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Profiles.route,
                        onClick = {
                            navController.navigate(Screen.Profiles.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Shield, contentDescription = "Profiles") },
                        label = { Text("Profiles") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Tags.route,
                        onClick = {
                            navController.navigate(Screen.Tags.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Nfc, contentDescription = "Tags") },
                        label = { Text("NFC Hub") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Setup.route,
                        onClick = {
                            navController.navigate(Screen.Setup.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Setup") },
                        label = { Text("Setup") }
                    )
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
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
