package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.FileEntry
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FileExplorerScreen(
    vm: FileExplorerViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
) {
    val currentPath by vm.currentPath.collectAsState()
    val entries by vm.entries.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        if (selectedSerial == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Strings.t("no_device_selected"), style = MaterialTheme.typography.body2)
            }
            return@Surface
        }
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // Breadcrumb: back + path + refresh
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.back() }) { Text("←") }
                Text(currentPath, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.refresh() }) { Text(Strings.t("refresh")) }
            }
            error?.let { e ->
                Surface(color = Color(0xFFFFCDD2), modifier = Modifier.fillMaxWidth()) {
                    Text(e, style = MaterialTheme.typography.caption, modifier = Modifier.padding(6.dp))
                }
            }
            Divider()
            // File list
            val density = androidx.compose.ui.platform.LocalDensity.current
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(entries) { _, entry ->
                    var menuOpen by remember { mutableStateOf(false) }
                    var clickX by remember { mutableStateOf(0f) }
                    var clickY by remember { mutableStateOf(0f) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { if (entry.isDirectory || entry.permissions.startsWith("l")) vm.navigate("${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${entry.name}") }
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                                            val pos = event.changes.firstOrNull()?.position
                                            if (pos != null) { clickX = pos.x; clickY = pos.y }
                                            menuOpen = true
                                        }
                                    }
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(when {
                            entry.permissions.startsWith("l") -> "🔗"
                            entry.isDirectory -> "📁"
                            else -> "📄"
                        }, modifier = Modifier.padding(end = 8.dp))
                        Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.body2)
                        Text("${entry.size}", style = MaterialTheme.typography.caption, modifier = Modifier.padding(end = 8.dp))
                        Text(entry.date, style = MaterialTheme.typography.caption)
                    }
                    // Right-click context menu (positioned at the click location)
                    DropdownMenu(
                        expanded = menuOpen,
                        offset = androidx.compose.ui.unit.DpOffset(with(density) { clickX.toDp() }, with(density) { clickY.toDp() }),
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(onClick = {
                            menuOpen = false
                            val dlg = FileDialog(Frame(), "Upload", FileDialog.LOAD)
                            dlg.isVisible = true
                            if (dlg.file != null) vm.push("${dlg.directory}${dlg.file}")
                        }) { Text(Strings.t("upload")) }
                        if (!entry.isDirectory) {
                            DropdownMenuItem(onClick = {
                                menuOpen = false
                                val dlg = FileDialog(Frame(), "Save", FileDialog.SAVE)
                                dlg.file = entry.name
                                dlg.isVisible = true
                                if (dlg.file != null) vm.pull("${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${entry.name}", "${dlg.directory}${dlg.file}")
                            }) { Text(Strings.t("save_file")) }
                        }
                        DropdownMenuItem(onClick = {
                            menuOpen = false
                            vm.refresh()
                        }) { Text(Strings.t("refresh")) }
                        DropdownMenuItem(onClick = {
                            menuOpen = false
                            val path = "${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${entry.name}"
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(path), null)
                        }) { Text(Strings.t("copy_path")) }
                    }
                }
            }
        }
    }
}
