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
     * Tries the modern `IFileOpenDialog` first (real Explorer window with a typeable breadcrumb
     * address bar — AWT's `FileDialog` only gives the old "Look in:" dropdown). On ANY failure
     * (COM interop is hand-written vtable dispatch; if the unverified offsets are wrong we want to
     * degrade, not crash) it logs to stderr and falls back to the legacy AWT FileDialog so the app
     * never breaks — worst case the user sees the old dialog + a log line we can diagnose.
     *
     * @param title dialog title.
     * @param currentPath a path the dialog should open at (e.g. the value currently in the text
     *   field); its directory is used. Blank/null → OS default location.
     * @param filePattern optional filter pattern e.g. `"*.apk"`. Only honored by the legacy
     *   fallback (the modern dialog would need SetFileTypes, not yet wired). Modern dialog shows
     *   all files; a wrong pick surfaces an inline install error.
     */
    fun pickFile(title: String, currentPath: String?, filePattern: String? = null): String? {
        // Distinguish "modern dialog succeeded (returns a path OR null if user cancelled)" from
        // "modern dialog threw" — only the latter falls back, else a cancel would re-open legacy.
        val result = runCatching { WindowsFilePicker.pickFile(title, currentPath) }
        if (result.isSuccess) return result.getOrNull()
        System.err.println("[FileDialogs] modern picker failed, falling back to legacy: ${result.exceptionOrNull()?.message}")
        return pickFileLegacy(title, currentPath, filePattern)
    }

    /**
     * Open the native folder picker, auto-navigated to [currentPath]. Returns the chosen absolute
     * path, or `null` if the user cancelled.
     *
     * Uses the modern `IFileOpenDialog` with `FOS_PICKFOLDERS` (real Explorer window). On COM
     * failure, falls back to Swing `JFileChooser` in DIRECTORIES_ONLY mode — AWT `FileDialog`
     * has no native folder-only mode, so JFileChooser is the only JDK fallback. The fallback
     * lives here in the platform layer (not in UI code) so the UI never touches Swing directly.
     */
    fun pickDirectory(title: String, currentPath: String?): String? {
        val result = runCatching { WindowsFilePicker.pickDirectory(title, currentPath) }
        if (result.isSuccess) return result.getOrNull()
        System.err.println("[FileDialogs] modern folder picker failed, falling back to JFileChooser: ${result.exceptionOrNull()?.message}")
        return pickDirectoryLegacy(title, currentPath)
    }

    private fun pickDirectoryLegacy(title: String, currentPath: String?): String? {
        val chooser = javax.swing.JFileChooser()
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        chooser.dialogTitle = title
        chooser.approveButtonText = "Select folder"
        parentDirOf(currentPath)?.let { chooser.currentDirectory = java.io.File(it) }
        return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.absolutePath
        } else null
    }

    private fun pickFileLegacy(title: String, currentPath: String?, filePattern: String?): String? {
        val dlg = FileDialog(Frame(), title, FileDialog.LOAD)
        parentDirOf(currentPath)?.let { dlg.directory = it }
        if (filePattern != null) dlg.file = filePattern
        dlg.isVisible = true
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
