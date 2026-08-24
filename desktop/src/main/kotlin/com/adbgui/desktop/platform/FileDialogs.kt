package com.adbgui.desktop.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Native Windows file picker built on `java.awt.FileDialog`.
 *
 * On Windows, `FileDialog` IS the OS common open/save dialog — the real Explorer window with a
 * breadcrumb address bar at the top. Click the breadcrumb's empty area (or the current folder name
 * in it) and it turns into an editable field where you can type or paste a full path. This is what
 * users expect when they ask for "资源管理器，地址栏可以输入的那种" — not Swing's `JFileChooser`,
 * which renders its own non-native panel.
 *
 * `setDirectory` makes the dialog open at the directory of the currently-entered path so users
 * don't have to click-navigate from a default location each time.
 */
object FileDialogs {

    /**
     * Open the native file-open dialog, auto-navigated to the directory of [currentPath] (its parent
     * if it points at a file). Returns the chosen absolute path, or `null` if the user cancelled.
     *
     * @param title dialog title.
     * @param currentPath a path the dialog should open at (e.g. the value currently in the text
     *   field); its directory is used. Blank/null → OS default location.
     * @param filePattern optional Win32 filter pattern e.g. `"*.apk"` to restrict to APK files.
     */
    fun pickFile(title: String, currentPath: String?, filePattern: String? = null): String? {
        val dlg = FileDialog(Frame(), title, FileDialog.LOAD)
        parentDirOf(currentPath)?.let { dlg.directory = it }
        if (filePattern != null) dlg.file = filePattern
        dlg.isVisible = true
        // dlg.file is null when the user cancelled; otherwise it's the chosen file name (the pattern
        // set above is only the initial filter, replaced by the actual selection on OK).
        val sel = dlg.file ?: return null
        return File(dlg.directory, sel).absolutePath
    }

    /** The directory to open the dialog at: [path] itself if it's a dir, else its parent. Null if
     *  blank/unknown so the OS picks a default. */
    private fun parentDirOf(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        val dir = when {
            f.isDirectory -> f
            f.parentFile != null -> f.parentFile
            else -> return null
        }
        return dir.absolutePath
    }
}
