package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Device Info screen: shows a grid of [com.adbgui.core.domain.DeviceProps] fields for the
 * selected device. Refresh triggers [DeviceInfoViewModel.load]. Inline error from `vm.error`.
 */
@Composable
fun DeviceInfoScreen(
    vm: DeviceInfoViewModel,
    modifier: Modifier = Modifier,
    onOpenScreenshot: () -> Unit = {},
    screenshotLoading: Boolean = false,
) {
    val props by vm.props.collectAsState()
    val error by vm.error.collectAsState()
    val report by vm.report.collectAsState()
    val exportBusy by vm.exportBusy.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Strings.t("device_info"), style = MaterialTheme.typography.h6)
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        vm.load().invokeOnCompletion { busy = false }
                    },
                ) { Text(Strings.t("refresh")) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = props != null && !exportBusy,
                    onClick = { vm.export() },
                ) { Text(Strings.t("export")) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !screenshotLoading,
                    onClick = onOpenScreenshot,
                ) { Text(Strings.t("screenshot")) }
                if (screenshotLoading) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.width(18.dp))
                }
                if (exportBusy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.width(18.dp))
                }
            }

            report?.let { reportText ->
                Surface(
                    color = MaterialTheme.colors.background,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            Strings.t("report_ready").format(reportText.length),
                            style = MaterialTheme.typography.caption,
                        )
                        Row {
                            OutlinedButton(onClick = {
                                val dialog = FileDialog(Frame(), Strings.t("export_device_info_title"), FileDialog.SAVE)
                                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                                dialog.file = "deviceinfo_$stamp.txt"
                                dialog.isVisible = true
                                val sel = dialog.file
                                if (sel != null) {
                                    val target = File(dialog.directory, sel)
                                    runCatching { target.writeText(reportText) }
                                        .onSuccess { savedFile = target; saveError = null }
                                        .onFailure { saveError = Strings.t("status_save_failed").format(it.message) }
                                }
                            }) { Text(Strings.t("save")) }
                        }
                        savedFile?.let { f ->
                            Spacer(Modifier.width(8.dp))
                            SavedFileBanner(path = f.absolutePath, onOpen = { openFile(f) }, onReveal = { revealFile(f) })
                        }
                    }
                }
            }

            val saveOrErr = error ?: saveError
            saveOrErr?.let { msg ->
                InlineMessageBanner(
                    Strings.t("adb_error"),
                    MessageKind.Error,
                    details = msg,
                    initiallyExpanded = true,
                )
            }

            Divider()

            val p = props
            if (p == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(Strings.t("no_device_selected_refresh"), style = MaterialTheme.typography.body2)
                }
            } else {
                PropRow(Strings.t("prop_brand"), p.brand)
                PropRow(Strings.t("prop_manufacturer"), p.manufacturer)
                PropRow(Strings.t("prop_model"), p.model)
                PropRow(Strings.t("prop_android_version"), p.androidVersion)
                PropRow(Strings.t("prop_sdk"), p.sdkInt.toString())
                PropRow(Strings.t("prop_serial"), p.serial)
                PropRow(Strings.t("prop_resolution"), p.resolution)
                PropRow(Strings.t("prop_abi"), p.abi)
            }
        }
    }
}

@Composable
private fun PropRow(label: String, value: String) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.width(160.dp),
        )
        Text(value, style = MaterialTheme.typography.body1, modifier = Modifier.weight(1f))
        IconButton(onClick = {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
            copied = true
        }) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = Strings.t("copy"),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
