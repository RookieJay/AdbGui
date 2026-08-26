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
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Screenshot viewer shown in the independent [ScreenshotWindow]. Capture is triggered before
 * the window opens (from Device Overview), so by the time this composes a shot is usually ready.
 * Bottom-right actions: copy (to clipboard) and save (default focus). No capture button here.
 */
@Composable
fun ScreenshotScreen(
    vm: ScreenshotViewModel,
    modifier: Modifier = Modifier,
) {
    val image by vm.image.collectAsState()
    val error by vm.error.collectAsState()
    var savedFile by remember { mutableStateOf<File?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var copyStatus by remember { mutableStateOf<String?>(null) }
    val saveFocus = remember { FocusRequester() }

    // Default focus to Save so Enter saves immediately.
    LaunchedEffect(Unit) { saveFocus.requestFocus() }
    // Clear the copy status hint after a short delay so it reads as transient feedback.
    LaunchedEffect(copyStatus) {
        if (copyStatus != null) {
            delay(2000)
            copyStatus = null
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Image area (grows to fill).
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bytes = image
                if (bytes == null) {
                    SelectableText(
                        error ?: Strings.t("no_screenshot"),
                        style = MaterialTheme.typography.body2,
                    )
                } else {
                    val bitmap = remember(bytes) {
                        runCatching { Image.makeFromEncoded(bytes).asImageBitmap() }.getOrNull()
                    }
                    if (bitmap != null) {
                        // ContentScale.Inside: fit within the area preserving aspect, but never
                        // upscale beyond the bitmap's native resolution — fillMaxWidth would
                        // stretch/upscale a 1920-wide PNG in a wider window and look blurry.
                        Image(
                            bitmap = bitmap,
                            contentDescription = Strings.t("content_screenshot"),
                            contentScale = ContentScale.Inside,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(Strings.t("decode_failed").format(bytes.size), style = MaterialTheme.typography.body2)
                    }
                }
            }

            // Post-save links: open the image and its folder.
            savedFile?.let { f ->
                SavedFileBanner(
                    path = f.absolutePath,
                    onOpen = { openFile(f) },
                    onReveal = { revealFile(f) },
                    openLabel = Strings.t("open_image"),
                )
            }

            // Inline save error.
            saveError?.let { InlineMessageBanner(it, MessageKind.Error) }

            // Bottom-right actions: copy, save (default focus).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                copyStatus?.let {
                    SelectableText(it, style = MaterialTheme.typography.caption)
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(
                    enabled = image != null,
                    onClick = {
                        image?.let { bytes ->
                            copyStatus = if (copyImageToClipboard(bytes)) Strings.t("copied")
                            else Strings.t("copy_failed")
                        }
                    },
                ) { Text(Strings.t("copy")) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = image != null,
                    onClick = {
                        image?.let { bytes ->
                            val dialog = FileDialog(Frame(), Strings.t("save_screenshot_title"), FileDialog.SAVE)
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            dialog.file = "screenshot_$stamp.png"
                            dialog.isVisible = true
                            val sel = dialog.file
                            if (sel != null) {
                                val target = File(dialog.directory, sel)
                                runCatching { target.writeBytes(bytes) }
                                    .onSuccess { savedFile = target; saveError = null }
                                    .onFailure { saveError = Strings.t("status_save_failed").format(it.message) }
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(saveFocus),
                ) { Text(Strings.t("save")) }
            }
        }
    }
}
