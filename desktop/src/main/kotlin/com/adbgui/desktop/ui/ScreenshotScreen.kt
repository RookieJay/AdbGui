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
import java.awt.FileDialog
import java.awt.Frame

/**
 * Screenshot screen: Capture triggers [ScreenshotViewModel.capture]; the PNG is rendered via
 * `Image(bitmap = bytes.toImageBitmap())`. Save opens a [FileDialog] and writes the bytes.
 */
@Composable
fun ScreenshotScreen(
    vm: ScreenshotViewModel,
    modifier: Modifier = Modifier,
) {
    val image by vm.image.collectAsState()
    val error by vm.error.collectAsState()
    var busy by remember { mutableStateOf(false) }

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
                        vm.capture().invokeOnCompletion { busy = false }
                    },
                ) { Text("Capture") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = image != null,
                    onClick = {
                        image?.let { bytes ->
                            val dialog = FileDialog(Frame(), "Save screenshot", FileDialog.SAVE)
                            dialog.file = "screenshot.png"
                            dialog.isVisible = true
                            val sel = dialog.file
                            if (sel != null) {
                                val target = java.io.File(dialog.directory, sel)
                                target.writeBytes(bytes)
                            }
                        }
                    },
                ) { Text("Save") }
                if (busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.width(18.dp))
                }
            }

            error?.let { msg ->
                Surface(
                    color = Color(0xFFFFCDD2),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
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
