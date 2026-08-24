package websnag.elopenmike.com.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import websnag.elopenmike.com.core.model.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = WebSnagViolet,
    onPrimary = WebSnagGroundEdge,
    primaryContainer = WebSnagGroundCore,
    onPrimaryContainer = WebSnagTypeLight,
    secondary = WebSnagVioletPrint,
    onSecondary = Color.White,
    background = WebSnagGroundEdge,
    onBackground = WebSnagTypeLight,
    surface = WebSnagGroundCore,
    onSurface = WebSnagTypeLight,
    surfaceVariant = Color(0xFF352B56),
    onSurfaceVariant = Slate400,
    outline = Color(0xFF4A3E72),
    outlineVariant = Color(0xFF352B56),
    error = RoseBlock,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = WebSnagVioletPrint,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = WebSnagVioletPrint,
    secondary = WebSnagViolet,
    onSecondary = Slate900,
    background = LightBackground,
    onBackground = Slate900,
    surface = LightSurface,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate700,
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = RoseBlock,
    onError = Color.White
)

@Composable
fun WebSnagTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    darkTheme: Boolean = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    },
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
