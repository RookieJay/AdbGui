package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch
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
    // Filter UI state is LOCAL (synchronous) — vm.setFilters is async on serialDispatcher
    // (the L4 concurrency fix), so binding inputs to the controller's StateFlow made checkboxes
    // not uncheck and textfields not type. Local state drives the inputs; setFilters applies async.
    var levelSet by remember { mutableStateOf(LogcatLevel.entries.toSet()) }
    var text by remember { mutableStateOf("") }
    var levelMenuOpen by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Filter bar (AS-style: level dropdown + one text input + controls)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Level dropdown (multi-select)
                Box {
                    OutlinedButton(onClick = { levelMenuOpen = true }) {
                        Text("${Strings.t("level")}: ${levelLabel(levelSet)}")
                    }
                    DropdownMenu(expanded = levelMenuOpen, onDismissRequest = { levelMenuOpen = false }) {
                        LogcatLevel.entries.forEach { lvl ->
                            DropdownMenuItem(onClick = {
                                val ns = if (lvl in levelSet) levelSet - lvl else levelSet + lvl
                                levelSet = ns
                                vm.setFilters(LogcatFilters(levelSet = ns, text = text.ifBlank { null }))
                            }) {
                                Text("${if (lvl in levelSet) "✓" else "•"}  ${lvl.name}")
                            }
                        }
                    }
                }
                // One text input (matches across the raw line: tag + message + timestamp + pid)
                TextField(
                    value = text, singleLine = true,
                    onValueChange = {
                        text = it
                        vm.setFilters(LogcatFilters(levelSet = levelSet, text = it.ifBlank { null }))
                    },
                    label = { Text(Strings.t("text_search")) },
                    modifier = Modifier.width(220.dp),
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
                        runCatching { t.writeText(vm.export()) }
                            .onSuccess { savedFile = t; exportError = null }
                            .onFailure { exportError = Strings.t("status_save_failed").format(it.message) }
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
                    SelectableText(e, Modifier.padding(6.dp), style = MaterialTheme.typography.caption)
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

            exportError?.let { msg ->
                Surface(color = Color(0xFFFFCDD2), shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                    SelectableText(msg, style = MaterialTheme.typography.caption, modifier = Modifier.padding(6.dp))
                }
            }

            Divider()
            // Log list with auto-scroll-to-bottom
            val listState = rememberLazyListState()
            val userAtBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lines.isEmpty() || lastVisible >= lines.size - 2   // at/near bottom → auto-scroll ok
                }
            }
            LaunchedEffect(lines.size) {
                if (userAtBottom && lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
            }
            val scrollScope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { _, line ->
                        Text(line.raw, color = levelColor(line.level), style = MaterialTheme.typography.body2)
                    }
                }
                // Floating "jump to latest" button when the user has scrolled up.
                if (!userAtBottom && lines.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { scrollScope.launch { listState.animateScrollToItem(lines.size - 1) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Text("↓") }
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

private fun levelLabel(levelSet: Set<LogcatLevel>): String =
    if (levelSet.size == LogcatLevel.entries.size) Strings.t("level_all")
    else levelSet.joinToString(",") { it.name }
