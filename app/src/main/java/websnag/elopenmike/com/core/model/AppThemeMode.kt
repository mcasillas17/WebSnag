package websnag.elopenmike.com.core.model

import kotlinx.serialization.Serializable

/**
 * User-selected theme mode for WebSnag.
 */
@Serializable
enum class AppThemeMode {
    /** Follow Android system light/dark mode */
    SYSTEM,

    /** Force clean, high-contrast light theme */
    LIGHT,

    /** Force OLED-friendly violet-ink dark theme */
    DARK
}
