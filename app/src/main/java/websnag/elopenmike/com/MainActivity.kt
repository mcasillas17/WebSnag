package websnag.elopenmike.com

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import websnag.elopenmike.com.core.activity.ActivityAttestation
import websnag.elopenmike.com.core.activity.AndroidKeystoreActivitySigner
import websnag.elopenmike.com.core.backup.BackupException
import websnag.elopenmike.com.core.backup.BackupRepository
import websnag.elopenmike.com.core.privacy.PrivacyStatus
import websnag.elopenmike.com.core.nfc.NfcPayloadHelper
import websnag.elopenmike.com.core.nfc.NfcTagAction
import websnag.elopenmike.com.core.enforcement.EndRequest
import websnag.elopenmike.com.ui.activity.ActivityScreen
import websnag.elopenmike.com.ui.activity.ActivityViewModel
import websnag.elopenmike.com.ui.dashboard.DashboardScreen
import websnag.elopenmike.com.ui.dashboard.DashboardViewModel
import websnag.elopenmike.com.ui.navigation.Screen
import websnag.elopenmike.com.ui.profiles.ProfileEditorScreen
import websnag.elopenmike.com.ui.profiles.ProfilesScreen
import websnag.elopenmike.com.ui.profiles.ProfilesViewModel
import websnag.elopenmike.com.ui.privacy.PrivacyScreen
import websnag.elopenmike.com.ui.setup.PermissionsScreen
import websnag.elopenmike.com.ui.tags.EnrollTagScreen
import websnag.elopenmike.com.ui.tags.TagsScreen
import websnag.elopenmike.com.ui.tags.TagsViewModel
import websnag.elopenmike.com.ui.theme.WebSnagTheme

import androidx.compose.material.icons.filled.CalendarMonth
import websnag.elopenmike.com.ui.schedule.ScheduleEditorScreen
import websnag.elopenmike.com.ui.schedule.ScheduleScreen
import websnag.elopenmike.com.ui.schedule.ScheduleViewModel

class MainActivity : ComponentActivity() {

    private lateinit var app: WebSnagApp
    private var pendingBackupBytes: ByteArray? = null
    private var pendingImportPassphrase: CharArray? = null
    private val activityExportJson = Json { encodeDefaults = true }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBackupBytes
        pendingBackupBytes = null
        if (uri == null || bytes == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: throw IOException("The selected document cannot be opened for writing.")
                withContext(Dispatchers.Main) { showMessage("Export saved.") }
            } catch (exception: IOException) {
                withContext(Dispatchers.Main) { showMessage("Export failed: ${exception.message}") }
            } catch (exception: SecurityException) {
                withContext(Dispatchers.Main) { showMessage("Export failed: document access was denied.") }
            } catch (exception: IllegalArgumentException) {
                withContext(Dispatchers.Main) { showMessage("Export failed: the selected document is invalid.") }
            }
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val passphrase = pendingImportPassphrase
        pendingImportPassphrase = null
        if (uri == null || passphrase == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.backupRepository.restore(readBoundedDocument(uri), passphrase)
                withContext(Dispatchers.Main) {
                    showMessage(
                        if (result == BackupRepository.RestoreResult.Restored) "Backup restored."
                        else "Restore refused: deactivate the active focus profile first."
                    )
                }
            } catch (exception: BackupException) {
                withContext(Dispatchers.Main) { showMessage("Restore failed: ${exception.message}") }
            } catch (exception: IOException) {
                withContext(Dispatchers.Main) { showMessage("Restore failed: ${exception.message}") }
            } catch (exception: SecurityException) {
                withContext(Dispatchers.Main) { showMessage("Restore failed: document access was denied.") }
            } catch (exception: IllegalArgumentException) {
                withContext(Dispatchers.Main) { showMessage("Restore failed: the selected document is invalid.") }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as WebSnagApp

        // Observe foreground NFC scans
        lifecycleScope.launch {
            app.nfcManager.scannedTagFlow.collectLatest { scanned ->
                handleScannedTag(scanned.uidHex, scanned.customPayload)
            }
        }

        setContent {
            val themeMode by app.localDataStore.themeModeFlow.collectAsState(initial = websnag.elopenmike.com.core.model.AppThemeMode.SYSTEM)
            WebSnagTheme(themeMode = themeMode) {
                MainAppContent(
                    app = app,
                    currentThemeMode = themeMode,
                    onThemeModeSelected = { newMode ->
                        lifecycleScope.launch {
                            app.localDataStore.setThemeMode(newMode)
                        }
                    },
                    internetPermissionDeclared = declaredPermissions().internetPermissionDeclared,
                    onExportBackup = ::exportBackup,
                    onImportBackup = ::requestBackupImport,
                    onExportActivity = ::exportActivityAttestation,
                    onDeleteHistory = ::deleteHistory,
                    onDeleteAllData = ::deleteAllData
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        app.nfcManager.enableReaderMode(this)
    }

    override fun onPause() {
        super.onPause()
        app.nfcManager.disableReaderMode(this)
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
                    if (app.enforcementEngine.requestEnd(
                            action.profile.id,
                            EndRequest.Nfc(
                                tagId = app.nfcTagRepository.getTagForUid(uidHex)?.id.orEmpty(),
                                isEnrolled = true
                            )
                        )
                    ) {
                        Toast.makeText(this@MainActivity, "Unlocked: ${action.profile.name}", Toast.LENGTH_SHORT).show()
                    }
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

    private fun exportBackup(passphrase: String, includeHistory: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = app.backupRepository.export(passphrase.toCharArray(), includeHistory)
                withContext(Dispatchers.Main) {
                    pendingBackupBytes = bytes
                    createDocument.launch("websnag-backup.wsb")
                }
            } catch (exception: BackupException) {
                withContext(Dispatchers.Main) { showMessage("Export failed: ${exception.message}") }
            }
        }
    }

    private fun requestBackupImport(passphrase: String) {
        pendingImportPassphrase?.fill('\u0000')
        pendingImportPassphrase = passphrase.toCharArray()
        openDocument.launch(arrayOf("application/octet-stream", "application/x-websnag-backup"))
    }

    private fun exportActivityAttestation() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val records = app.localDataStore.createBackupSnapshot(includeHistory = true).history
                val export = ActivityAttestation.create(records, AndroidKeystoreActivitySigner())
                val bytes = activityExportJson.encodeToString(export).encodeToByteArray()
                withContext(Dispatchers.Main) {
                    pendingBackupBytes = bytes
                    createDocument.launch("websnag-activity-attestation.json")
                }
            } catch (exception: IllegalStateException) {
                withContext(Dispatchers.Main) { showMessage("Activity export failed: ${exception.message}") }
            }
        }
    }

    private fun deleteHistory() {
        lifecycleScope.launch {
            if (app.profileRepository.activeProfileFlow.first() != null) {
                showMessage("Delete history is unavailable while a focus profile is active.")
                return@launch
            }
            app.localDataStore.deleteFocusHistory()
            showMessage("Focus history deleted.")
        }
    }

    private fun deleteAllData() {
        lifecycleScope.launch {
            if (app.profileRepository.activeProfileFlow.first() != null) {
                showMessage("Delete all data is unavailable while a focus profile is active.")
                return@launch
            }
            app.localDataStore.deleteAllUserData()
            showMessage("All WebSnag data deleted.")
        }
    }

    @Suppress("DEPRECATION")
    private fun declaredPermissions(): PrivacyStatus {
        val permissions = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()
        return PrivacyStatus.fromDeclaredPermissions(permissions)
    }

    @Throws(IOException::class)
    private fun readBoundedDocument(uri: android.net.Uri): ByteArray {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IOException("The selected document cannot be opened.")
        return stream.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                if (output.size() + read > 1_048_576) throw IOException("The selected backup is too large.")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
fun MainAppContent(
    app: WebSnagApp,
    currentThemeMode: websnag.elopenmike.com.core.model.AppThemeMode = websnag.elopenmike.com.core.model.AppThemeMode.SYSTEM,
    onThemeModeSelected: (websnag.elopenmike.com.core.model.AppThemeMode) -> Unit = {},
    internetPermissionDeclared: Boolean = false,
    onExportBackup: (String, Boolean) -> Unit = { _, _ -> },
    onImportBackup: (String) -> Unit = {},
    onExportActivity: () -> Unit = {},
    onDeleteHistory: () -> Unit = {},
    onDeleteAllData: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val dashboardViewModel = remember {
        DashboardViewModel(app.profileRepository, app.nfcTagRepository, app.localDataStore, app.enforcementEngine)
    }
    val scheduleViewModel = remember {
        ScheduleViewModel(app.localDataStore, app.profileRepository, app.networkMonitor, app.enforcementEngine)
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
        Screen.Schedule.route,
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
                            .padding(vertical = 8.dp),
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
                            label = "Schedule",
                            icon = Icons.Default.CalendarMonth,
                            isSelected = currentRoute == Screen.Schedule.route,
                            onClick = {
                                if (currentRoute != Screen.Schedule.route) {
                                    navController.navigate(Screen.Schedule.route) {
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

                composable(Screen.Schedule.route) {
                    ScheduleScreen(
                        viewModel = scheduleViewModel,
                        onNavigateToAddSchedule = {
                            navController.navigate(Screen.ScheduleEditor.createRoute(null))
                        },
                        onNavigateToEditSchedule = { scheduleId ->
                            navController.navigate(Screen.ScheduleEditor.createRoute(scheduleId))
                        }
                    )
                }

                composable(
                    route = Screen.ScheduleEditor.route,
                    arguments = listOf(navArgument("scheduleId") {
                        type = NavType.StringType
                    })
                ) { backStackEntry ->
                    val scheduleId = backStackEntry.arguments?.getString("scheduleId")
                    ScheduleEditorScreen(
                        scheduleId = if (scheduleId == "new") null else scheduleId,
                        viewModel = scheduleViewModel,
                        onNavigateBack = { navController.popBackStack() }
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
                        onNavigateToPrivacy = { navController.navigate(Screen.Privacy.route) },
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Privacy.route) {
                    PrivacyScreen(
                        internetPermissionDeclared = internetPermissionDeclared,
                        onExportBackup = onExportBackup,
                        onImportBackup = onImportBackup,
                        onExportActivity = onExportActivity,
                        onDeleteHistory = onDeleteHistory,
                        onDeleteAllData = onDeleteAllData
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
