package com.adbgui.desktop.platform

import java.io.BufferedReader

/**
 * Windows-only system theme detection that reads the live registry value instead of relying on
 * Compose's [androidx.compose.foundation.isSystemInDarkTheme], which snapshots the AWT/Swing
 * theme at JVM startup and does NOT refresh when the user switches the OS dark/light mode at
 * runtime (so "follow system" would stay stale until app restart).
 *
 * Reads `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`
 * (REG_DWORD: 0x1 = light, 0x0 = dark). The project is Windows-first; on other OSes this falls
 * back to [fallbackIsDark] (the Compose snapshot), which is still correct at startup — it just
 * won't live-update, matching the prior behavior.
 */
object SystemThemeDetector {
    private val valueRegex = Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9A-Fa-f]+)")

    fun isDark(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        if (!os.startsWith("windows")) return false // Windows-first; non-Windows falls back to light
        return try {
            val proc = ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use(BufferedReader::readText)
            proc.waitFor()
            val light = valueRegex.find(out)?.groupValues?.get(1)?.toIntOrNull(16)
            // light == 1 → not dark; light == 0 → dark; missing → assume light (safe default)
            light == 0
        } catch (e: Exception) {
            false // registry read failed — assume light rather than guessing
        }
    }
}
