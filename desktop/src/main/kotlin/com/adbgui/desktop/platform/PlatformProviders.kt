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
    // v1 ships without a bundled adb; returns null so locator falls back to PATH/override.
    // To bundle later: place adb.exe in desktop/src/main/resources/adb/win/adb.exe and resolve here.
    override fun bundledAdbPath(): String? = null
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
