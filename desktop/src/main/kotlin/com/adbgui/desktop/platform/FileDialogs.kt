package com.adbgui.desktop.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Native file/folder pickers. After the hand-rolled IFileOpenDialog COM approach was removed
 * (unverified vtable offsets + JNA generic-invoke marshaling gaps made it crash and fall back,
 * causing a double-dialog), we use AWT directly: one reliable native dialog, no crash, no
 * second dialog. On Windows the AWT FileDialog renders the legacy "Look in:" style — that's the
 * trade-off for reliability; drag-and-drop is the modern install path in the UI.
 */
object FileDialogs {

    /**
     * Open the native file-open dialog, auto-navigated to the directory of [currentPath] (its
     * parent if it points at a file). Returns the chosen absolute path, or `null` if the user
     * cancelled.
     */
    fun pickFile(title: String, currentPath: String?, filePattern: String? = null): String? {
        val dlg = FileDialog(Frame(), title, FileDialog.LOAD)
        parentDirOf(currentPath)?.let { dlg.directory = it }
        if (filePattern != null) dlg.file = filePattern  // AWT uses filename as a pattern filter hint
        dlg.isVisible = true
        val sel = dlg.file ?: return null
        return File(dlg.directory, sel).absolutePath
    }

    /**
     * Open the native save dialog, auto-navigated to the directory of [currentPath] (its
     * parent if it points at a file), with [defaultName] pre-filled as the filename. Returns
     * the chosen absolute path, or `null` if the user cancelled.
     */
    fun saveFile(title: String, defaultName: String? = null, currentPath: String? = null): String? {
        val dlg = FileDialog(Frame(), title, FileDialog.SAVE)
        parentDirOf(currentPath)?.let { dlg.directory = it }
        if (defaultName != null) dlg.file = defaultName
        dlg.isVisible = true
        val sel = dlg.file ?: return null
        return File(dlg.directory, sel).absolutePath
    }

    /**
     * Open the native folder picker. AWT's [FileDialog] has no folder-only mode, so Swing
     * [javax.swing.JFileChooser] in DIRECTORIES_ONLY is the only JDK option — kept here in the
     * platform layer so the UI never touches Swing directly. Auto-navigates to [currentPath].
     */
    fun pickDirectory(title: String, currentPath: String?): String? {
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
