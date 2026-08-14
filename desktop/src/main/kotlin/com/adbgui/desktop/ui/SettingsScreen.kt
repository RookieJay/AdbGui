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
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
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
    var levelMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.h6)

            // --- ADB path ---
            Text("ADB binary path", style = MaterialTheme.typography.subtitle1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = adbDraft,
                    onValueChange = { adbDraft = it },
                    label = { Text("adb path override") },
                    placeholder = { Text("(use bundled / system PATH)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val parent = Frame()
                    val dialog = FileDialog(parent, "Select adb binary", FileDialog.LOAD)
                    dialog.isMultipleMode = false
                    dialog.isVisible = true
                    val sel = dialog.file
                    if (sel != null) {
                        val dir = dialog.directory
                        val chosen = File(dir, sel).absolutePath
                        adbDraft = chosen
                        vm.setAdbPath(chosen)
                        status = "adb path set: $chosen"
                    }
                }) { Text("Browse") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = {
                    val path = adbDraft.trim().ifBlank { null }
                    vm.setAdbPath(path)
                    status = if (path == null) "adb override cleared" else "adb path set: $path"
                }) { Text("Apply") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    adbDraft = ""
                    vm.setAdbPath(null)
                    status = "adb override cleared"
                }) { Text("Clear") }
            }

            Divider()

            // --- Log level ---
            Text("Log level", style = MaterialTheme.typography.subtitle1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current: ${settings.logLevel.name}", modifier = Modifier.padding(end = 12.dp))
                TextButton(onClick = { levelMenu = true }) { Text("Change") }
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    LogLevel.entries.forEach { lvl ->
                        DropdownMenuItem(onClick = {
                            levelMenu = false
                            vm.setLogLevel(lvl)
                            status = "log level set: ${lvl.name}"
                        }) { Text(lvl.name) }
                    }
                }
            }

            Divider()

            // --- Logs ---
            Text("Logs", style = MaterialTheme.typography.subtitle1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val logDir = configDir.resolve("logs").toFile()
                    runCatching {
                        if (!logDir.exists()) logDir.mkdirs()
                        Desktop.getDesktop().open(logDir)
                    }.onFailure { status = "open failed: ${it.message}" }
                }) { Text("Open logs folder") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val logDir = configDir.resolve("logs").toFile()
                    if (!logDir.exists() || logDir.listFiles()?.isNotEmpty() == false) {
                        status = "no logs to export"
                        return@Button
                    }
                    val parent = Frame()
                    val dialog = FileDialog(parent, "Save logs zip", FileDialog.SAVE)
                    dialog.file = "adbgui-logs.zip"
                    dialog.isVisible = true
                    val sel = dialog.file
                    if (sel != null) {
                        val target = File(dialog.directory, sel)
                        runCatching { zipDirectory(logDir, target) }
                            .onSuccess { status = "exported to ${target.absolutePath}" }
                            .onFailure { status = "export failed: ${it.message}" }
                    }
                }) { Text("Export logs") }
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
