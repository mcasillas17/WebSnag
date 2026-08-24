package org.websnag.core.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.websnag.core.model.AppCategory
import org.websnag.core.model.AppInfo

/**
 * Queries and caches launchable installed applications on the device.
 */
class InstalledAppsRepository(private val context: Context) {

    private var cachedApps: List<AppInfo>? = null

    suspend fun getInstalledApps(forceRefresh: Boolean = false): List<AppInfo> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedApps != null) {
            return@withContext cachedApps!!
        }

        val packageManager = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(mainIntent, 0)
        }

        val selfPackage = context.packageName

        val apps = resolveInfos.mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val packageName = activityInfo.packageName

            // Never include WebSnag itself or core phone emergency handler
            if (packageName == selfPackage) return@mapNotNull null

            val appName = resolveInfo.loadLabel(packageManager).toString().ifBlank { packageName }
            val icon = resolveInfo.loadIcon(packageManager)
            val isSystemApp = try {
                val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (e: Exception) {
                false
            }

            val category = categorizePackage(packageName)

            AppInfo(
                packageName = packageName,
                appName = appName,
                isSystemApp = isSystemApp,
                icon = icon,
                category = category
            )
        }.distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }

        cachedApps = apps
        apps
    }

    private fun categorizePackage(packageName: String): AppCategory {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("instagram") || pkg.contains("tiktok") || pkg.contains("musically") ||
            pkg.contains("twitter") || pkg.contains("facebook") || pkg.contains("snapchat") ||
            pkg.contains("reddit") || pkg.contains("threads") || pkg.contains("pinterest") ||
            pkg.contains("tumblr") || pkg.contains("social") -> AppCategory.SOCIAL

            pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("twitch") ||
            pkg.contains("disney") || pkg.contains("hulu") || pkg.contains("spotify") ||
            pkg.contains("primevideo") || pkg.contains("hbomax") || pkg.contains("stream") -> AppCategory.ENTERTAINMENT

            pkg.contains("game") || pkg.contains("play") || pkg.contains("arcade") ||
            pkg.contains("supercell") || pkg.contains("roblox") || pkg.contains("king.") -> AppCategory.GAMES

            pkg.contains("amazon") || pkg.contains("ebay") || pkg.contains("walmart") ||
            pkg.contains("target") || pkg.contains("shopping") || pkg.contains("shein") -> AppCategory.SHOPPING

            pkg.contains("news") || pkg.contains("nytimes") || pkg.contains("bbc") ||
            pkg.contains("cnn") || pkg.contains("reuters") -> AppCategory.NEWS

            pkg.contains("slack") || pkg.contains("notion") || pkg.contains("asana") ||
            pkg.contains("trello") || pkg.contains("todoist") || pkg.contains("jira") ||
            pkg.contains("office") || pkg.contains("docs") -> AppCategory.PRODUCTIVITY

            else -> AppCategory.OTHER
        }
    }
}
