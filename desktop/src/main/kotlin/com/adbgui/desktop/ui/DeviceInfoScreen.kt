package com.adbgui.desktop.ui

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
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
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
) {
    val props by vm.props.collectAsState()
    val error by vm.error.collectAsState()
    val report by vm.report.collectAsState()
    val exportBusy by vm.exportBusy.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var errorExpanded by remember { mutableStateOf(true) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Device Info", style = MaterialTheme.typography.h6)
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        vm.load().invokeOnCompletion { busy = false }
                    },
                ) { Text("Refresh") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = props != null && !exportBusy,
                    onClick = { vm.export() },
                ) { Text("Export") }
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
                            "Report ready (${reportText.length} chars)",
                            style = MaterialTheme.typography.caption,
                        )
                        Row {
                            OutlinedButton(onClick = {
                                val dialog = FileDialog(Frame(), "Export device info", FileDialog.SAVE)
                                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                                dialog.file = "deviceinfo_$stamp.txt"
                                dialog.isVisible = true
                                val sel = dialog.file
                                if (sel != null) {
                                    val target = File(dialog.directory, sel)
                                    runCatching { target.writeText(reportText) }
                                        .onSuccess { savedFile = target; saveError = null }
                                        .onFailure { saveError = "Save failed: ${it.message}" }
                                }
                            }) { Text("Save") }
                        }
                        savedFile?.let { f ->
                            Spacer(Modifier.width(8.dp))
                            Text("Saved: ${f.absolutePath}", style = MaterialTheme.typography.caption)
                            Row {
                                TextButton(onClick = { openFile(f) }) { Text("Open") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { revealFile(f) }) { Text("Open folder") }
                            }
                        }
                    }
                }
            }

            val saveOrErr = error ?: saveError
            saveOrErr?.let { msg ->
                Surface(
                    color = Color(0xFFFFCDD2),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("adb error", style = MaterialTheme.typography.subtitle2)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { errorExpanded = !errorExpanded }) {
                                Text(if (errorExpanded) "Collapse" else "Expand")
                            }
                        }
                        if (errorExpanded) {
                            Text(msg, style = MaterialTheme.typography.caption)
                        }
                    }
                }
            }

            Divider()

            val p = props
            if (p == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No device selected. Press Refresh.", style = MaterialTheme.typography.body2)
                }
            } else {
                PropRow("Model", p.model)
                PropRow("Android version", p.androidVersion)
                PropRow("SDK", p.sdkInt.toString())
                PropRow("Serial", p.serial)
                PropRow("Resolution", p.resolution)
                PropRow("ABI", p.abi)
            }
        }
    }
}

@Composable
private fun PropRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.width(160.dp),
        )
        Text(value, style = MaterialTheme.typography.body1)
    }
}
