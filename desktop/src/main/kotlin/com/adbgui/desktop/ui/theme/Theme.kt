package com.adbgui.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Semantic color slots that Material 2's [androidx.compose.material.Colors] does not provide:
 * error/success/info container pairs (for inline banners) and logcat severity colors.
 * Both light and dark variants must exist — [logInfo] is plain black in light mode, which is
 * invisible on a dark surface, so any dark theme MUST override these.
 */
data class ExtendedColors(
    val errorContainer: Color,
    val onErrorContainer: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val logVerbose: Color,
    val logDebug: Color,
    val logInfo: Color,
    val logWarn: Color,
    val logError: Color,
)

val LightExtendedColors = ExtendedColors(
    errorContainer = Color(0xFFFFCDD2),
    onErrorContainer = Color(0xFFB00020),
    successContainer = Color(0xFFC8E6C9),
    onSuccessContainer = Color(0xFF1B5E20),
    infoContainer = Color(0xFFF0F0F0),
    onInfoContainer = Color(0xFF424242),
    logVerbose = Color(0xFF9E9E9E),
    logDebug = Color(0xFF9E9E9E),
    logInfo = Color(0xFF1B1B1B),
    logWarn = Color(0xFFE65100),
    logError = Color(0xFFC62828),
)

val DarkExtendedColors = ExtendedColors(
    errorContainer = Color(0xFF5A1A1A),
    onErrorContainer = Color(0xFFFFB4B4),
    successContainer = Color(0xFF1E3A1E),
    onSuccessContainer = Color(0xFFB4E6B4),
    infoContainer = Color(0xFF2A2A2A),
    onInfoContainer = Color(0xFFE0E0E0),
    logVerbose = Color(0xFF9E9E9E),
    logDebug = Color(0xFFB0B0B0),
    logInfo = Color(0xFFE0E0E0),
    logWarn = Color(0xFFFFB74D),
    logError = Color(0xFFEF9A9A),
)

val LocalExtendedColors = compositionLocalOf<ExtendedColors> {
    error("ExtendedColors not provided; wrap content in AdbGuiTheme")
}

private val LightColors = lightColors(
    primary = Color(0xFF2F5D8A),
    primaryVariant = Color(0xFF1B3D5C),
    secondary = Color(0xFF5B6B7D),
    background = Color(0xFFF5F6F7),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1A1C1E),
    onSurface = Color(0xFF1A1C1E),
    error = Color(0xFFB00020),
    onError = Color.White,
)

private val DarkColors = darkColors(
    primary = Color(0xFF8AB4E8),
    primaryVariant = Color(0xFF9DB4D6),
    secondary = Color(0xFF8B98A8),
    background = Color(0xFF121316),
    surface = Color(0xFF1A1C1F),
    onPrimary = Color(0xFF002B5C),
    onSecondary = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E3E6),
    onSurface = Color(0xFFE2E3E6),
    error = Color(0xFFCF6679),
    onError = Color(0xFF1A1C1E),
)

/** Persisted theme preference; mirrors `Settings.theme`. */
enum class ThemePref(val code: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromCode(code: String?): ThemePref =
            entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

/** Resolve a persisted theme code (light/dark/system) to a concrete dark-or-not value. */
@Composable
fun resolveIsDark(themeCode: String): Boolean = when (ThemePref.fromCode(themeCode)) {
    ThemePref.LIGHT -> false
    ThemePref.DARK -> true
    ThemePref.SYSTEM -> isSystemInDarkTheme()
}

/**
 * App-wide theme: provides Material 2 [MaterialTheme] colors (light/dark) plus [ExtendedColors]
 * via [LocalExtendedColors]. Must wrap every top-level Window so dark mode is consistent.
 */
@Composable
fun AdbGuiTheme(
    themeCode: String,
    content: @Composable () -> Unit,
) {
    val isDark = resolveIsDark(themeCode)
    val colors = if (isDark) DarkColors else LightColors
    val extended = if (isDark) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(colors = colors) {
            content()
        }
    }
}

/** Convenience accessor for extended colors, parallel to `MaterialTheme.colors`. */
object AppColors {
    val current: ExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current
}
