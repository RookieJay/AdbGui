package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.Extra
import com.adbgui.core.domain.ExtraType
import com.adbgui.core.domain.PackageInfo
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.FileDialog
import java.awt.Frame

/**
 * App Console screen: upper package list (selectable) + lower operation panel with
 * Start/Stop/Restart/Clear/Uninstall and a collapsible "Advanced" section hosting
 * `am start`, broadcast, and provider query. Errors/results shown via [SelectableText].
 */
@Composable
fun AppConsoleScreen(
    vm: AppConsoleViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
) {
    val packages by vm.packages.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val broadcastResult by vm.broadcastResult.collectAsState()
    val providerResult by vm.providerResult.collectAsState()
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var errorExpanded by remember { mutableStateOf(true) }
    var advancedOpen by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
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
                        val dialog = FileDialog(Frame(), Strings.t("select_apk"), FileDialog.LOAD)
                        dialog.isMultipleMode = false
                        dialog.setFile("*.apk")
                        dialog.isVisible = true
                        val sel = dialog.file
                        if (sel != null) {
                            val chosen = java.io.File(dialog.directory, sel).absolutePath
                            vm.install(chosen)
                        }
                    },
                ) { Text(Strings.t("select_apk")) }
                Spacer(Modifier.width(8.dp))
                if (busy) CircularProgressIndicator(modifier = Modifier.heightIn(max = 18.dp))
            }

            // --- Inline error (collapsible) ---
            error?.let { msg ->
                Surface(
                    color = Color(0xFFFFCDD2),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Strings.t("adb_error"), style = MaterialTheme.typography.subtitle2)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { errorExpanded = !errorExpanded }) {
                                Text(if (errorExpanded) Strings.t("collapse") else Strings.t("expand"))
                            }
                        }
                        if (errorExpanded) {
                            SelectableText(msg, style = MaterialTheme.typography.caption)
                        }
                    }
                }
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
                        OutlinedButton(enabled = !busy, onClick = { vm.clearData(sel) }) { Text(Strings.t("clear")) }
                        OutlinedButton(enabled = !busy, onClick = { vm.uninstall(sel); selectedPkg = null }) {
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
