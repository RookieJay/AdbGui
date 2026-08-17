package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.adbgui.core.device.LogcatFilters
import com.adbgui.core.device.LogcatStatus
import com.adbgui.core.domain.LogcatLevel
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LogcatScreen(vm: LogcatViewModel, modifier: Modifier = Modifier) {
    val lines by vm.lines.collectAsState()
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val filters by vm.filters.collectAsState()
    var savedFile by remember { mutableStateOf<File?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Filter bar
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Strings.t("level"), style = MaterialTheme.typography.caption)
                LogcatLevel.entries.forEach { lvl ->
                    val checked = lvl in filters.levelSet
                    Checkbox(checked, onCheckedChange = { v ->
                        val ns = if (v) filters.levelSet + lvl else filters.levelSet - lvl
                        vm.setFilters(filters.copy(levelSet = ns))
                    })
                    Text(lvl.name, style = MaterialTheme.typography.caption)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(
                    value = filters.tagInclude ?: "", singleLine = true,
                    onValueChange = { vm.setFilters(filters.copy(tagInclude = it.ifBlank { null })) },
                    label = { Text(Strings.t("tag_include")) }, modifier = Modifier.width(120.dp)
                )
                TextField(
                    value = filters.tagExclude ?: "", singleLine = true,
                    onValueChange = { vm.setFilters(filters.copy(tagExclude = it.ifBlank { null })) },
                    label = { Text(Strings.t("tag_exclude")) }, modifier = Modifier.width(120.dp)
                )
                TextField(
                    value = filters.text ?: "", singleLine = true,
                    onValueChange = { vm.setFilters(filters.copy(text = it.ifBlank { null })) },
                    label = { Text(Strings.t("text_search")) }, modifier = Modifier.width(140.dp)
                )
                TextField(
                    value = filters.pid?.toString() ?: "", singleLine = true,
                    onValueChange = { vm.setFilters(filters.copy(pid = it.toIntOrNull())) },
                    label = { Text(Strings.t("pid")) }, modifier = Modifier.width(80.dp)
                )
                Spacer(Modifier.weight(1f))
                val paused = status == LogcatStatus.PAUSED
                OutlinedButton(onClick = { if (paused) vm.resume() else vm.pause() }) {
                    Text(if (paused) Strings.t("resume") else Strings.t("pause"))
                }
                OutlinedButton(onClick = { vm.clear() }) { Text(Strings.t("clear")) }
                OutlinedButton(onClick = {
                    val dlg = FileDialog(Frame(), Strings.t("save_logcat_title"), FileDialog.SAVE)
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    dlg.file = "logcat_$stamp.txt"
                    dlg.isVisible = true
                    val sel = dlg.file
                    if (sel != null) {
                        val t = File(dlg.directory, sel)
                        runCatching { t.writeText(vm.export()) }.onSuccess { savedFile = t }
                    }
                }) { Text(Strings.t("export")) }
                OutlinedButton(onClick = {
                    val sel = StringSelection(lines.joinToString("\n") { it.raw })
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                }) { Text(Strings.t("copy")) }
            }

            if (status == LogcatStatus.RECONNECTING || status == LogcatStatus.FAILED) {
                Surface(color = MaterialTheme.colors.background, modifier = Modifier.fillMaxWidth()) {
                    Text(Strings.t("reconnecting_logcat"), Modifier.padding(6.dp), style = MaterialTheme.typography.caption)
                }
            }
            error?.let { e ->
                Surface(color = Color(0xFFFFCDD2), modifier = Modifier.fillMaxWidth()) {
                    Text(e, Modifier.padding(6.dp), style = MaterialTheme.typography.caption)
                }
            }
            savedFile?.let { f ->
                Surface(color = MaterialTheme.colors.background, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Strings.t("saved_path").format(f.absolutePath), style = MaterialTheme.typography.caption)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { openFile(f) }) { Text(Strings.t("open")) }
                        TextButton(onClick = { revealFile(f) }) { Text(Strings.t("open_folder")) }
                    }
                }
            }

            Divider()
            // Log list with auto-scroll-to-bottom
            val listState = rememberLazyListState()
            LaunchedEffect(lines.size) {
                if (!listState.isScrollInProgress && lines.isNotEmpty()) {
                    listState.animateScrollToItem(lines.size - 1)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(lines, key = { it.raw.hashCode() }) { line ->
                    Text(line.raw, color = levelColor(line.level), style = MaterialTheme.typography.body2)
                }
            }
        }
    }
}

private fun levelColor(l: LogcatLevel): Color = when (l) {
    LogcatLevel.V, LogcatLevel.D -> Color.Gray
    LogcatLevel.I -> Color.Black
    LogcatLevel.W -> Color(0xFFE65100)
    LogcatLevel.E, LogcatLevel.F -> Color(0xFFC62828)
}
