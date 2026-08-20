package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.core.log.LogLevel
import com.adbgui.desktop.ui.i18n.Locale
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Settings screen: adb path override, log level, open/export logs.
 *
 * @param vm the settings view model.
 * @param configDir the app config dir; logs live at `configDir/logs`.
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    configDir: Path,
    modifier: Modifier = Modifier,
) {
    val settings by vm.settings.collectAsState()
    var adbDraft by remember(settings.adbPathOverride) {
        mutableStateOf(settings.adbPathOverride.orEmpty())
    }
    var scrcpyDraft by remember(settings.scrcpyPathOverride) {
        mutableStateOf(settings.scrcpyPathOverride.orEmpty())
    }
    var levelMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Strings.t("settings"), style = MaterialTheme.typography.h6)

            // --- Language ---
            Text(Strings.t("language"), style = MaterialTheme.typography.subtitle1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Locale.entries.forEach { locale ->
                    TextButton(onClick = { vm.setLocale(locale) }) {
                        Text(
                            text = locale.display,
                            color = if (settings.locale == locale.code) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }

            Divider()

            // --- ADB path ---
            Text(Strings.t("adb_binary_path"), style = MaterialTheme.typography.subtitle1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = adbDraft,
                    onValueChange = { adbDraft = it },
                    label = { Text(Strings.t("adb_path_override_label")) },
                    placeholder = { Text(Strings.t("adb_path_placeholder")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val parent = Frame()
                    val dialog = FileDialog(parent, Strings.t("select_adb_binary"), FileDialog.LOAD)
                    dialog.isMultipleMode = false
                    dialog.isVisible = true
                    val sel = dialog.file
                    if (sel != null) {
                        val dir = dialog.directory
                        val chosen = File(dir, sel).absolutePath
                        adbDraft = chosen
                        vm.setAdbPath(chosen)
                        status = Strings.t("status_adb_path_set").format(chosen)
                    }
                }) { Text(Strings.t("browse")) }
                Spacer(Modifier.width(4.dp))
                Button(onClick = {
                    val path = adbDraft.trim().ifBlank { null }
                    vm.setAdbPath(path)
                    status = if (path == null) Strings.t("status_adb_cleared") else Strings.t("status_adb_path_set").format(path)
                }) { Text(Strings.t("apply")) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    adbDraft = ""
                    vm.setAdbPath(null)
                    status = Strings.t("status_adb_cleared")
                }) { Text(Strings.t("clear")) }
            }

            Divider()

            // --- scrcpy path override ---
            Text(Strings.t("scrcpy"), style = MaterialTheme.typography.subtitle1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = scrcpyDraft,
                    onValueChange = { scrcpyDraft = it },
                    label = { Text(Strings.t("scrcpy_manual_path")) },
                    placeholder = { Text(Strings.t("scrcpy_path_placeholder")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    // JFileChooser (not AWT FileDialog): its "File name" field accepts a pasted
                    // full path + Enter, so users can paste a copied scrcpy.exe path directly.
                    val chooser = JFileChooser()
                    chooser.isAcceptAllFileFilterUsed = false
                    chooser.dialogTitle = Strings.t("select_scrcpy_binary")
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val chosen = chooser.selectedFile.absolutePath
                        scrcpyDraft = chosen
                        vm.setScrcpyPath(chosen)
                        status = Strings.t("status_scrcpy_path_set").format(chosen)
                    }
                }) { Text(Strings.t("browse")) }
                Spacer(Modifier.width(4.dp))
                Button(onClick = {
                    val path = scrcpyDraft.trim().ifBlank { null }
                    vm.setScrcpyPath(path)
                    status = if (path == null) Strings.t("status_scrcpy_path_cleared")
                    else Strings.t("status_scrcpy_path_set").format(path)
                }) { Text(Strings.t("apply")) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    scrcpyDraft = ""
                    vm.setScrcpyPath(null)
                    status = Strings.t("status_scrcpy_path_cleared")
                }) { Text(Strings.t("clear")) }
            }

            Divider()

            // --- Log level ---
            Text(Strings.t("log_level"), style = MaterialTheme.typography.subtitle1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Strings.t("current").format(settings.logLevel.name), modifier = Modifier.padding(end = 12.dp))
                TextButton(onClick = { levelMenu = true }) { Text(Strings.t("change")) }
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    LogLevel.entries.forEach { lvl ->
                        DropdownMenuItem(onClick = {
                            levelMenu = false
                            vm.setLogLevel(lvl)
                            status = Strings.t("status_log_level_set").format(lvl.name)
                        }) { Text(lvl.name) }
                    }
                }
            }

            Divider()

            // --- Logs ---
            Text(Strings.t("logs"), style = MaterialTheme.typography.subtitle1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val logDir = configDir.resolve("logs").toFile()
                    runCatching {
                        if (!logDir.exists()) logDir.mkdirs()
                        Desktop.getDesktop().open(logDir)
                    }.onFailure { status = Strings.t("status_open_failed").format(it.message) }
                }) { Text(Strings.t("open_logs_folder")) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val logDir = configDir.resolve("logs").toFile()
                    if (!logDir.exists() || logDir.listFiles()?.isNotEmpty() == false) {
                        status = Strings.t("status_no_logs")
                        return@Button
                    }
                    val parent = Frame()
                    val dialog = FileDialog(parent, Strings.t("save_logs_zip"), FileDialog.SAVE)
                    dialog.file = "adbgui-logs.zip"
                    dialog.isVisible = true
                    val sel = dialog.file
                    if (sel != null) {
                        val target = File(dialog.directory, sel)
                        runCatching { zipDirectory(logDir, target) }
                            .onSuccess { status = Strings.t("status_exported_to").format(target.absolutePath) }
                            .onFailure { status = Strings.t("status_export_failed").format(it.message) }
                    }
                }) { Text(Strings.t("export_logs")) }
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.caption)
            }
        }
    }
}

private fun zipDirectory(sourceDir: File, zipFile: File) {
    val files = sourceDir.listFiles()?.toList().orEmpty()
    ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
        files.filter { it.isFile }.forEach { f ->
            zos.putNextEntry(ZipEntry(f.name))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
