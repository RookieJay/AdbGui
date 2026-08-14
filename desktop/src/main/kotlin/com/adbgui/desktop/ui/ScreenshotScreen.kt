package com.adbgui.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Screenshot screen: Capture triggers [ScreenshotViewModel.capture]; the PNG is rendered via
 * `Image(bitmap = bytes.toImageBitmap())`. Save opens a [FileDialog] with a timestamped default
 * name, writes the bytes, and shows clickable links to open the image and its folder.
 */
@Composable
fun ScreenshotScreen(
    vm: ScreenshotViewModel,
    modifier: Modifier = Modifier,
) {
    val image by vm.image.collectAsState()
    val error by vm.error.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Screenshot", style = MaterialTheme.typography.h6)
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        savedFile = null
                        saveError = null
                        vm.capture().invokeOnCompletion { busy = false }
                    },
                ) { Text("Capture") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = image != null,
                    onClick = {
                        image?.let { bytes ->
                            val dialog = FileDialog(Frame(), "Save screenshot", FileDialog.SAVE)
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            dialog.file = "screenshot_$stamp.png"
                            dialog.isVisible = true
                            val sel = dialog.file
                            if (sel != null) {
                                val target = File(dialog.directory, sel)
                                runCatching { target.writeBytes(bytes) }
                                    .onSuccess { savedFile = target; saveError = null }
                                    .onFailure { saveError = "Save failed: ${it.message}" }
                            }
                        }
                    },
                ) { Text("Save") }
                if (busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.width(18.dp))
                }
            }

            // Post-save links: open the image and its folder.
            savedFile?.let { f ->
                Surface(
                    color = MaterialTheme.colors.background,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Saved: ${f.absolutePath}",
                            style = MaterialTheme.typography.caption,
                        )
                        Row {
                            TextButton(onClick = { openFile(f) }) { Text("Open image") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { revealFile(f) }) { Text("Open folder") }
                        }
                    }
                }
            }

            // Inline errors (capture or save).
            val msg = error ?: saveError
            msg?.let {
                Surface(
                    color = Color(0xFFFFCDD2),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            Divider()

            val bytes = image
            if (bytes == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No screenshot yet. Press Capture.", style = MaterialTheme.typography.body2)
                }
            } else {
                val bitmap = remember(bytes) {
                    runCatching { Image.makeFromEncoded(bytes).asImageBitmap() }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Device screenshot",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Failed to decode image (${bytes.size} bytes)", style = MaterialTheme.typography.body2)
                    }
                }
            }
        }
    }
}

/** Opens [file] in the OS default application (image viewer for a PNG). No-op if unsupported. */
private fun openFile(file: File) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return
    runCatching { desktop.open(file) }
}

/**
 * Reveals [file] in the OS file manager, selecting it where possible.
 * Windows: `explorer.exe /select,<path>`; others: open the parent directory.
 */
private fun revealFile(file: File) {
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    if (isWindows) {
        runCatching {
            ProcessBuilder(listOf("explorer.exe", "/select,${file.absolutePath}"))
                .redirectErrorStream(true)
                .start()
        }
        return
    }
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return
    runCatching { desktop.open(file.parentFile ?: file) }
}
