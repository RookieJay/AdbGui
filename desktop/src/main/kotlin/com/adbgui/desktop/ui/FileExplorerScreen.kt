package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.FileEntry
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FileExplorerScreen(
    vm: FileExplorerViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
    onOpenConnect: () -> Unit = {},
) {
    val currentPath by vm.currentPath.collectAsState()
    val entries by vm.entries.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val savedFile by vm.savedFile.collectAsState()
    var pendingPush by remember { mutableStateOf<Pair<String, String>?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        if (selectedSerial == null) {
            EmptyState(
                title = Strings.t("no_device_selected"),
                hint = Strings.t("no_device_hint"),
                icon = Icons.Filled.Folder,
                actionLabel = Strings.t("connect_first_device"),
                onAction = onOpenConnect,
            )
            return@Surface
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Toolbar: back + clickable breadcrumb + upload + refresh
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Strings.t("back"))
                }
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Split into ancestor segments; each navigates to its cumulative path.
                    val segs = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                    var acc = ""
                    // Root always navigable.
                    Text(
                        "/",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.clickable { vm.navigate("/") },
                    )
                    segs.forEach { seg ->
                        acc = if (acc.isEmpty()) "/$seg" else "$acc/$seg"
                        val target = acc
                        Text(
                            " / ",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        )
                        Text(
                            seg,
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.primary,
                            modifier = Modifier.clickable { vm.navigate(target) },
                        )
                    }
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                OutlinedButton(onClick = {
                    val localPath = com.adbgui.desktop.platform.FileDialogs.pickFile(title = Strings.t("upload"), currentPath = null)
                    if (localPath != null) {
                        val name = localPath.substringAfterLast(File.separator)
                        val target = "${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}$name"
                        pendingPush = localPath to target
                    }
                }) { Text(Strings.t("upload")) }
                OutlinedButton(onClick = { vm.refresh() }) { Text(Strings.t("refresh")) }
            }
            error?.let { e -> InlineMessageBanner(e, MessageKind.Error) }
            savedFile?.let { f ->
                SavedFileBanner(path = f.absolutePath, onOpen = { openFile(f) }, onReveal = { revealFile(f) })
            }
            Divider()

            // Sort state (UI-only): directories always grouped first, each group by the field.
            var sortField by remember { mutableStateOf(SortField.NAME) }
            var sortAsc by remember { mutableStateOf(true) }

            // Column header (sortable). Click: same field toggles asc/desc, new field resets to asc.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortHeader(Strings.t("col_name"), sortField == SortField.NAME, sortAsc, Modifier.weight(1f)) {
                    if (sortField == SortField.NAME) sortAsc = !sortAsc else { sortField = SortField.NAME; sortAsc = true }
                }
                SortHeader(Strings.t("col_size"), sortField == SortField.SIZE, sortAsc, Modifier.width(80.dp)) {
                    if (sortField == SortField.SIZE) sortAsc = !sortAsc else { sortField = SortField.SIZE; sortAsc = true }
                }
                SortHeader(Strings.t("col_date"), sortField == SortField.DATE, sortAsc, Modifier.width(120.dp)) {
                    if (sortField == SortField.DATE) sortAsc = !sortAsc else { sortField = SortField.DATE; sortAsc = true }
                }
            }
            Divider()

            val sorted = remember(entries, sortField, sortAsc) {
                val cmp = when (sortField) {
                    SortField.NAME -> compareBy<FileEntry> { it.name.lowercase() }
                    SortField.SIZE -> compareBy<FileEntry> { it.size }
                    SortField.DATE -> compareBy<FileEntry> { it.date }
                }
                val dirs = entries.filter { it.isDirectory }.sortedWith(cmp)
                val files = entries.filter { !it.isDirectory }.sortedWith(cmp)
                val sd = if (sortAsc) dirs else dirs.reversed()
                val sf = if (sortAsc) files else files.reversed()
                sd + sf
            }

            // File list
            val density = androidx.compose.ui.platform.LocalDensity.current
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(sorted) { _, entry ->
                    var menuOpen by remember { mutableStateOf(false) }
                    var clickX by remember { mutableStateOf(0f) }
                    var clickY by remember { mutableStateOf(0f) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { if (entry.isDirectory) vm.navigate("${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${entry.name}") }
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
                        FileEntryIcon(entry, modifier = Modifier.padding(end = 8.dp))
                        Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.body2)
                        Text(
                            if (entry.isDirectory) "" else formatSize(entry.size),
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.width(80.dp),
                        )
                        Text(entry.date, style = MaterialTheme.typography.caption, modifier = Modifier.width(120.dp))
                        // Right-click context menu: download + copy path (upload/refresh live in toolbar).
                        DropdownMenu(
                            expanded = menuOpen,
                            offset = androidx.compose.ui.unit.DpOffset(with(density) { clickX.toDp() }, with(density) { clickY.toDp() }),
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(onClick = {
                                menuOpen = false
                                val saved = com.adbgui.desktop.platform.FileDialogs.saveFile(
                                    title = Strings.t("save_file"),
                                    defaultName = entry.name,
                                )
                                if (saved != null) {
                                    vm.pull("${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${entry.name}", saved)
                                }
                            }) { Text(Strings.t("save_file")) }
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

    pendingPush?.let { (localPath, target) ->
        AlertDialog(
            onDismissRequest = { pendingPush = null },
            title = { Text(Strings.t("push_confirm_title")) },
            text = { Text(Strings.t("push_confirm_body").format(target)) },
            confirmButton = {
                TextButton(onClick = { vm.push(localPath); pendingPush = null }) { Text(Strings.t("upload")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPush = null }) { Text(Strings.t("cancel")) }
            },
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

/**
 * Folder/file row icon. Folder → [Icons.Filled.Folder], file → [Icons.Filled.InsertDriveFile].
 * Symlinks get a small primary-colored link badge in the bottom-right corner on a surface circle
 * so the link semantics are clear without the font-dependent emoji combos ("🔗📁") that were here.
 */
@Composable
private fun FileEntryIcon(entry: FileEntry, modifier: Modifier = Modifier) {
    val baseIcon: ImageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile
    Box(modifier = modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Icon(baseIcon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colors.onSurface)
        if (entry.isSymlink) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).size(11.dp)
                    .background(MaterialTheme.colors.surface, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(9.dp), tint = MaterialTheme.colors.primary)
            }
        }
    }
}

private enum class SortField { NAME, SIZE, DATE }

/**
 * Sortable column header. The active field is primary-colored and shows an asc/desc arrow; an
 * inactive field is muted. [modifier] carries the column width (weight or fixed) so the header
 * aligns with the row cells below — must be called from a RowScope.
 */
@Composable
private fun RowScope.SortHeader(
    label: String,
    active: Boolean,
    asc: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            color = if (active) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
        if (active) {
            Text(
                if (asc) "▲" else "▼",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary,
            )
        }
    }
}
