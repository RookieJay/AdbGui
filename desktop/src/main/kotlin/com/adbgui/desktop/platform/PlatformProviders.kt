package com.adbgui.desktop.platform

import com.adbgui.core.adb.BundledAdbProvider
import com.adbgui.core.adb.PathProbe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

class WindowsConfigDirProvider {
    fun configDir(): Path {
        val appdata = System.getenv("APPDATA")
        val base = if (appdata != null) Path.of(appdata, "AdbGui") else Path.of(System.getProperty("user.home"), ".adbgui")
        Files.createDirectories(base)
        return base
    }
}

class ResourceBundledAdbProvider : BundledAdbProvider {
    // Resolves platform-tools adb shipped inside the native distribution.
    // compose.application.resources.dir is set by the Compose runtime in a packaged
    // distribution (AppImage/MSI); it is null under desktopRun, so in dev the locator
    // falls back to PATH/override — no local adb needed for development.
    override fun bundledAdbPath(): String? {
        val resDir = System.getProperty("compose.application.resources.dir") ?: return null
        val exe = Path.of(resDir, "adb", "win", "adb.exe")
        return if (Files.isExecutable(exe)) exe.pathString else null
    }
}

class SystemPathProbe : PathProbe {
    override fun existsOnPath(name: String): String? {
        val exeName = if (System.getProperty("os.name").startsWith("Windows")) "$name.exe" else name
        val pathEnv = System.getenv("PATH") ?: return null
        for (dir in pathEnv.split(java.io.File.pathSeparator)) {
            val candidate = Path.of(dir).resolve(exeName)
            if (Files.isExecutable(candidate)) return candidate.pathString
        }
        return null
    }
}
