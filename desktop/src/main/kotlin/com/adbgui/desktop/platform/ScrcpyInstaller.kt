package com.adbgui.desktop.platform

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

class ScrcpyInstaller(private val configDir: Path) {
    private val scrcpyDir get() = configDir.resolve("scrcpy")
    private val scrcpyExe get() = scrcpyDir.resolve("scrcpy-win64-v4.1/scrcpy.exe")

    fun isInstalled(): Boolean = Files.exists(scrcpyExe)

    fun ensureInstalled(): String {
        if (isInstalled()) return scrcpyExe.toString()
        return install()
    }

    fun install(): String {
        Files.createDirectories(scrcpyDir)
        val resource = javaClass.getResourceAsStream("/scrcpy/scrcpy-win64-v4.1.zip")
            ?: throw RuntimeException("scrcpy zip not found in resources")
        ZipInputStream(resource).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = scrcpyDir.resolve(entry.name)
                if (entry.isDirectory) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(zis, target)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return scrcpyExe.toString()
    }
}
