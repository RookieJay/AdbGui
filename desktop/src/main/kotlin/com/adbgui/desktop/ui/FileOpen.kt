package com.adbgui.desktop.ui

import java.awt.Desktop
import java.io.File

/** Opens [file] in the OS default application. No-op if unsupported. */
fun openFile(file: File) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return
    runCatching { desktop.open(file) }
}

/** Reveals [file] in the OS file manager, selecting it on Windows. */
fun revealFile(file: File) {
    if (System.getProperty("os.name").startsWith("Windows")) {
        runCatching {
            ProcessBuilder(listOf("explorer.exe", "/select,${file.absolutePath}"))
                .redirectErrorStream(true).start()
        }
        return
    }
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return
    runCatching { desktop.open(file.parentFile ?: file) }
}
