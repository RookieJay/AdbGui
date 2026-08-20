package com.adbgui.desktop.platform

import com.adbgui.core.adb.PathProbe
import com.adbgui.core.settings.SettingsStore
import java.nio.file.Files
import java.nio.file.Path

interface ScrcpyLocator {
    fun locate(): String?
}

class WindowsScrcpyLocator(
    private val settings: SettingsStore,
    private val configDir: Path,
    private val pathProbe: PathProbe,
) : ScrcpyLocator {
    private val scrcpyExe get() = configDir.resolve("scrcpy/scrcpy-win64-v4.1/scrcpy.exe")

    override fun locate(): String? {
        // 1. Override
        val override = kotlinx.coroutines.runBlocking { settings.load().scrcpyPathOverride }
        override?.takeIf { it.isNotBlank() && Files.exists(Path.of(it)) }?.let { return it }
        // 2. Extracted
        if (Files.exists(scrcpyExe)) return scrcpyExe.toString()
        // 3. PATH
        return pathProbe.existsOnPath("scrcpy")
    }
}
