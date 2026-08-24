package org.websnag.core.model

import android.graphics.drawable.Drawable

/**
 * Information regarding an installed launcher application on the user's device.
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val icon: Drawable? = null,
    val category: AppCategory = AppCategory.OTHER
)

enum class AppCategory {
    SOCIAL,
    ENTERTAINMENT,
    GAMES,
    PRODUCTIVITY,
    NEWS,
    SHOPPING,
    OTHER
}
