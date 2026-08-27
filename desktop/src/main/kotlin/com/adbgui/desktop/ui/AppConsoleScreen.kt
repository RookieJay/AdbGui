package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import com.adbgui.core.domain.Extra
import com.adbgui.core.domain.ExtraType
import com.adbgui.core.domain.PackageInfo
import com.adbgui.desktop.ui.i18n.Strings
import java.io.File

/**
 * App Console screen: upper package list (selectable) + lower operation panel with
 * Start/Stop/Restart/Clear/Uninstall and a collapsible "Advanced" section hosting
 * `am start`, broadcast, and provider query. Errors/results shown via [SelectableText].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppConsoleScreen(
    vm: AppConsoleViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
) {
    val packages by vm.packages.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    // Ephemeral success message auto-clears after a few seconds (toast-like); errors persist for
    // debugging until the next operation.
    LaunchedEffect(message) {
        if (message != null) { delay(6000); vm.clearMessage() }
    }
    val broadcastResult by vm.broadcastResult.collectAsState()
    val providerResult by vm.providerResult.collectAsState()
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var advancedOpen by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    var confirmClearData by remember { mutableStateOf<String?>(null) }
    var dragOver by remember { mutableStateOf(false) }

    // Drop APK files anywhere on the console to install — the modern path the button-picker
    // can't reliably be (the hand-rolled COM picker was removed). onEntered/Exited drive the
    // drop-zone highlight; onDrop filters to .apk files and installs each.
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = event.awtTransferable
                val apks = runCatching {
                    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                        ?.filterIsInstance<File>()
                }.getOrNull().orEmpty().filter { it.extension.equals("apk", ignoreCase = true) }
                if (apks.isEmpty()) return false
                apks.forEach { vm.install(it.absolutePath) }
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { dragOver = true }
            override fun onExited(event: DragAndDropEvent) { dragOver = false }
            override fun onEnded(event: DragAndDropEvent) { dragOver = false }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = { true },
            target = dropTarget,
        ),
        color = MaterialTheme.colors.surface,
    ) {
        if (selectedSerial == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Strings.t("no_device_selected"), style = MaterialTheme.typography.body2)
            }
            return@Surface
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Toolbar ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Strings.t("app_console"), style = MaterialTheme.typography.h6)
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = search,
                    singleLine = true,
                    onValueChange = { search = it },
                    placeholder = { Text(Strings.t("text_search")) },
                    modifier = Modifier.width(220.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy, onClick = { vm.load() }) { Text(Strings.t("refresh")) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        val chosen = com.adbgui.desktop.platform.FileDialogs.pickFile(
                            title = Strings.t("select_apk"),
                            currentPath = null,
                            filePattern = "*.apk",
                        )
                        if (chosen != null) vm.install(chosen)
                    },
                ) { Text(Strings.t("install_apk")) }
                Spacer(Modifier.width(8.dp))
                if (busy) CircularProgressIndicator(modifier = Modifier.heightIn(max = 18.dp))
            }

            // Drop zone: visible affordance that the whole screen accepts APK drops.
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp)
                    .border(
                        width = if (dragOver) 2.dp else 1.dp,
                        color = if (dragOver) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .background(
                        if (dragOver) MaterialTheme.colors.primary.copy(alpha = 0.08f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = if (dragOver) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (dragOver) Strings.t("drop_apk_to_install") else Strings.t("drop_apk_hint"),
                        style = MaterialTheme.typography.body2,
                        color = if (dragOver) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            // --- Inline result: error (collapsible) or success (ephemeral) — never both ---
            error?.let { msg ->
                InlineMessageBanner(
                    Strings.t("adb_error"),
                    MessageKind.Error,
                    details = msg,
                    initiallyExpanded = true,
                )
            } ?: message?.let { msg ->
                InlineMessageBanner(msg, MessageKind.Success)
            }

            Divider()

            // --- Package list (upper ~40%) ---
            val filtered = remember(packages, search) {
                if (search.isBlank()) packages
                else packages.filter { it.name.contains(search, ignoreCase = true) }
            }
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.4f)) {
                if (filtered.isEmpty() && !busy) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(Strings.t("no_packages"), style = MaterialTheme.typography.body2)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filtered, key = { it.name }) { pkg ->
                            PackageSelectRow(
                                pkg = pkg,
                                isSelected = pkg.name == selectedPkg,
                                onClick = { selectedPkg = pkg.name },
                            )
                            Divider()
                        }
                    }
                }
            }

            Divider()

            // --- Lower operation panel (~60%) ---
            val sel = selectedPkg
            if (sel == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(Strings.t("no_packages"), style = MaterialTheme.typography.body2)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        sel,
                        style = MaterialTheme.typography.subtitle1,
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { vm.startApp(sel) }) { Text(Strings.t("start_app")) }
                        Button(enabled = !busy, onClick = { vm.forceStop(sel) }) { Text(Strings.t("force_stop")) }
                        Button(enabled = !busy, onClick = { vm.restart(sel) }) { Text(Strings.t("restart_app")) }
                        OutlinedButton(enabled = !busy, onClick = { confirmClearData = sel }) { Text(Strings.t("clear")) }
                        OutlinedButton(enabled = !busy, onClick = { confirmUninstall = sel }) {
                            Text(Strings.t("uninstall"))
                        }
                    }

                    // --- Advanced (collapsible) ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { advancedOpen = !advancedOpen }) {
                            Text(if (advancedOpen) "▼ ${Strings.t("advanced_ops")}" else "▶ ${Strings.t("advanced_ops")}")
                        }
                    }
                    if (advancedOpen) {
                        AdvancedPanel(
                            pkg = sel,
                            busy = busy,
                            broadcastResult = broadcastResult,
                            providerResult = providerResult,
                            onStartActivity = { activity -> vm.startAppActivity(sel, activity) },
                            onSendBroadcast = { action, uri, extras -> vm.sendBroadcast(action, uri, extras) },
                            onQueryProvider = { uri, where -> vm.queryProvider(uri, where) },
                        )
                    }
                }
            }
        }
    }

    // Uninstall confirmation
    confirmUninstall?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text(Strings.t("uninstall")) },
            text = { Text("$pkg?") },
            confirmButton = {
                TextButton(onClick = { vm.uninstall(pkg); confirmUninstall = null }) { Text(Strings.t("uninstall")) }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text(Strings.t("cancel")) } },
        )
    }

    // Clear-data confirmation
    confirmClearData?.let { pkg ->
        AlertDialog(
            onDismissRequest = { confirmClearData = null },
            title = { Text(Strings.t("clear_data_confirm_title")) },
            text = { Text(Strings.t("clear_data_confirm_body").format(pkg)) },
            confirmButton = {
                TextButton(onClick = { vm.clearData(pkg); confirmClearData = null }) { Text(Strings.t("clear")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearData = null }) { Text(Strings.t("cancel")) }
            },
        )
    }
}

@Composable
private fun PackageSelectRow(
    pkg: PackageInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pkg.name, style = MaterialTheme.typography.body1)
            if (pkg.isSystem) {
                Text(Strings.t("system"), style = MaterialTheme.typography.caption)
            }
        }
    }
}

@Composable
private fun AdvancedPanel(
    pkg: String,
    busy: Boolean,
    broadcastResult: String?,
    providerResult: String?,
    onStartActivity: (String) -> Unit,
    onSendBroadcast: (String, String?, List<Extra>) -> Unit,
    onQueryProvider: (String, String?) -> Unit,
) {
    // am start
    var activity by remember { mutableStateOf("") }
    // broadcast
    var bAction by remember { mutableStateOf("") }
    var bUri by remember { mutableStateOf("") }
    val extrasRows = remember { mutableStateListOf<Triple<ExtraType, String, String>>() }
    // provider
    var pUri by remember { mutableStateOf("") }
    var pWhere by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colors.surface,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // --- am start ---
            Text(Strings.t("start_activity"), style = MaterialTheme.typography.subtitle2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = activity,
                    singleLine = true,
                    onValueChange = { activity = it },
                    label = { Text(Strings.t("activity_name")) },
                    placeholder = { Text("$pkg/.MainActivity") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !busy && activity.isNotBlank(),
                    onClick = { onStartActivity(activity.trim()) },
                ) { Text(Strings.t("start_activity")) }
            }

            Divider()

            // --- broadcast ---
            Text(Strings.t("send_broadcast"), style = MaterialTheme.typography.subtitle2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = bAction,
                    singleLine = true,
                    onValueChange = { bAction = it },
                    label = { Text(Strings.t("broadcast_action")) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = bUri,
                    singleLine = true,
                    onValueChange = { bUri = it },
                    label = { Text(Strings.t("broadcast_uri")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(Strings.t("extras"), style = MaterialTheme.typography.caption)
            extrasRows.forEachIndexed { index, row ->
                val (type, key, value) = row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var typeExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { typeExpanded = true }) { Text(type.flag) }
                        DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                            ExtraType.values().forEach { et ->
                                DropdownMenuItem(onClick = {
                                    extrasRows[index] = Triple(et, key, value)
                                    typeExpanded = false
                                }) { Text(et.flag) }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = key,
                        singleLine = true,
                        onValueChange = { extrasRows[index] = Triple(type, it, value) },
                        placeholder = { Text("key") },
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = value,
                        singleLine = true,
                        onValueChange = { extrasRows[index] = Triple(type, key, it) },
                        placeholder = { Text("value") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { extrasRows.removeAt(index) }) { Text(Strings.t("remove")) }
                }
            }
            Row {
                OutlinedButton(onClick = { extrasRows.add(Triple(ExtraType.STRING, "", "")) }) {
                    Text(Strings.t("add_button"))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !busy && bAction.isNotBlank(),
                    onClick = {
                        val extras = extrasRows
                            .filter { it.second.isNotBlank() }
                            .map { Extra(it.first, it.second.trim(), it.third) }
                        onSendBroadcast(bAction.trim(), bUri.ifBlank { null }, extras)
                    },
                ) { Text(Strings.t("send_broadcast")) }
            }
            broadcastResult?.let {
                Text(Strings.t("broadcast_result"), style = MaterialTheme.typography.caption)
                SelectableText(it)
            }

            Divider()

            // --- provider query ---
            Text(Strings.t("query_provider"), style = MaterialTheme.typography.subtitle2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pUri,
                    singleLine = true,
                    onValueChange = { pUri = it },
                    label = { Text(Strings.t("provider_uri")) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pWhere,
                    singleLine = true,
                    onValueChange = { pWhere = it },
                    label = { Text(Strings.t("provider_where")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row {
                Button(
                    enabled = !busy && pUri.isNotBlank(),
                    onClick = { onQueryProvider(pUri.trim(), pWhere.ifBlank { null }) },
                ) { Text(Strings.t("query_provider")) }
            }
            providerResult?.let {
                Text(Strings.t("provider_result"), style = MaterialTheme.typography.caption)
                SelectableText(it)
            }
        }
    }
}
