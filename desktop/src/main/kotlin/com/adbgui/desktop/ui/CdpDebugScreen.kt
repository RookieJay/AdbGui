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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.CdpConnectionState
import com.adbgui.core.domain.CdpConsoleEntry
import com.adbgui.core.domain.CdpLevel
import com.adbgui.core.domain.CdpNetworkRequest
import com.adbgui.core.domain.CdpTarget
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * CDP Debug page — surfaces the WebView's console + network + eval + reload via the
 * Chrome DevTools Protocol. Mirrors LogcatScreen (level-colored console list, filter,
 * clear, export) and PortForwardingScreen (dropdowns, inline error, Ctrl+Enter eval).
 *
 * Page-leave hygiene: [DisposableEffect] calls [CdpDebugViewModel.stop] on dispose to
 * tear down the ws + remove the one-click forward — no leaked sessions after navigation.
 */
@Composable
fun CdpDebugScreen(
    vm: CdpDebugViewModel,
    modifier: Modifier = Modifier,
) {
    val consoleEntries by vm.consoleEntries.collectAsState()
    val networkRequests by vm.networkRequests.collectAsState()
    val targets by vm.targets.collectAsState()
    val state by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    val evalResult by vm.evalResult.collectAsState()
    val responseBody by vm.responseBody.collectAsState()
    val selectedTargetId by vm.selectedTargetId.collectAsState()

    // Local UI state
    var filterText by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    // Frozen snapshot when paused; null = live
    var frozenConsole by remember { mutableStateOf<List<CdpConsoleEntry>?>(null) }
    var manualPort by remember { mutableStateOf("") }
    var targetsMenuOpen by remember { mutableStateOf(false) }
    var evalExpr by remember { mutableStateOf("") }
    var evalFrame by remember { mutableStateOf("") }
    var responseModalFor by remember { mutableStateOf<String?>(null) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val displayConsole = frozenConsole ?: consoleEntries
    val filteredConsole = remember(displayConsole, filterText) {
        val q = filterText.trim().lowercase()
        if (q.isEmpty()) displayConsole else displayConsole.filter { it.text.lowercase().contains(q) }
    }

    // Page-leave: stop the ws + remove the forward. The VM's stop() cancels the serial
    // collector (which cascades to the controller's run loop + transport via structured
    // concurrency) and drains in-flight callers.
    DisposableEffect(Unit) {
        onDispose { vm.stop() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ---- Top bar: state + targets + start/connect/reload ----
            CdpTopBar(
                state = state,
                targets = targets,
                selectedTargetId = selectedTargetId,
                onTargetsOpen = { targetsMenuOpen = true },
                targetsMenuOpen = targetsMenuOpen,
                onTargetsDismiss = { targetsMenuOpen = false },
                onSelectTarget = { targetsMenuOpen = false },  // display-only (v1 scope cut); TODO(v2): CdpController.switchPage(targetId) to reconnect page ws
                manualPort = manualPort,
                onManualPortChange = { manualPort = it.filter { c -> c.isDigit() } },
                onStart = { vm.start() },
                onConnect = { manualPort.toIntOrNull()?.let { vm.connectManual(it) } },
                onReload = { vm.reload() },
            )

            // ---- Inline error ----
            error?.let { msg ->
                InlineMessageBanner(
                    text = msg,
                    kind = MessageKind.Error,
                    onDismiss = { vm.clearError() },
                )
            }

            // ---- Reconnecting banner ----
            if (state == CdpConnectionState.RECONNECTING || state == CdpConnectionState.CONNECTING) {
                Surface(color = MaterialTheme.colors.background, modifier = Modifier.fillMaxWidth()) {
                    Text(Strings.t("cdp_reconnecting"), Modifier.padding(6.dp), style = MaterialTheme.typography.caption)
                }
            }

            savedFile?.let { f -> Text(f.absolutePath, style = MaterialTheme.typography.caption) }
            exportError?.let { msg -> InlineMessageBanner(msg, MessageKind.Error) }

            // ---- Main content: Console | Network ----
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // ---- Left: Console ----
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Console toolbar
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Strings.t("cdp_console"), style = MaterialTheme.typography.subtitle2)
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(
                            value = filterText,
                            onValueChange = { filterText = it },
                            singleLine = true,
                            placeholder = { Text(Strings.t("text_search")) },
                            modifier = Modifier.width(180.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            paused = !paused
                            frozenConsole = if (paused) consoleEntries else null
                        }) {
                            Icon(
                                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = Strings.t(if (paused) "resume" else "pause"),
                            )
                        }
                        IconButton(onClick = { vm.clearConsole() }) {
                            Icon(Icons.Filled.Delete, contentDescription = Strings.t("clear"))
                        }
                        IconButton(onClick = {
                            val sel = StringSelection(filteredConsole.joinToString("\n") { it.text })
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                        }) { Icon(Icons.Filled.ContentCopy, contentDescription = Strings.t("copy")) }
                        Button(onClick = {
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            val target = com.adbgui.desktop.platform.FileDialogs.saveFile(
                                title = Strings.t("cdp_console"),
                                defaultName = "cdp_console_$stamp.txt",
                            )
                            if (target != null) {
                                val t = File(target)
                                runCatching { t.writeText(filteredConsole.joinToString("\n") { it.text }) }
                                    .onSuccess { savedFile = t; exportError = null }
                                    .onFailure { exportError = Strings.t("status_save_failed").format(it.message) }
                            }
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(Strings.t("export"))
                        }
                    }

                    Divider(color = AppColors.current.divider)

                    // Console list with auto-scroll
                    val listState = rememberLazyListState()
                    val userAtBottom by remember {
                        derivedStateOf {
                            val info = listState.layoutInfo
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            filteredConsole.isEmpty() || lastVisible >= filteredConsole.size - 2
                        }
                    }
                    LaunchedEffect(filteredConsole.size) {
                        if (userAtBottom && filteredConsole.isNotEmpty() && !paused) {
                            listState.animateScrollToItem(filteredConsole.size - 1)
                        }
                    }
                    val scrollScope = rememberCoroutineScope()
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(filteredConsole, key = { it.id }) { entry ->
                                SelectableText(
                                    text = entry.text,
                                    color = cdpLevelColor(entry.level),
                                    style = MaterialTheme.typography.body2,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                )
                            }
                        }
                        if (!userAtBottom && filteredConsole.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { scrollScope.launch { listState.animateScrollToItem(filteredConsole.size - 1) } },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            ) { Text("↓") }
                        }
                    }
                }

                Divider(color = AppColors.current.divider, modifier = Modifier.fillMaxHeight().width(1.dp))

                // ---- Right: Network ----
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Strings.t("cdp_network"), style = MaterialTheme.typography.subtitle2)
                    Divider(color = AppColors.current.divider)
                    if (networkRequests.isEmpty()) {
                        Text(
                            "—",
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(networkRequests, key = { it.requestId }) { req ->
                                NetworkRow(req = req) {
                                    responseModalFor = req.requestId
                                    vm.getResponseBody(req.requestId)
                                }
                                Divider(color = AppColors.current.divider)
                            }
                        }
                    }
                }
            }

            // ---- Bottom: Eval ----
            Divider(color = AppColors.current.divider)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = evalExpr,
                    onValueChange = { evalExpr = it },
                    placeholder = { Text(Strings.t("cdp_eval_placeholder")) },
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                        if (e.key == Key.Enter && e.isCtrlPressed) {
                            vm.evaluate(evalExpr, evalFrame.ifBlank { null })
                            true
                        } else false
                    },
                )
                OutlinedTextField(
                    value = evalFrame,
                    onValueChange = { evalFrame = it },
                    singleLine = true,
                    placeholder = { Text(Strings.t("cdp_frames")) },
                    modifier = Modifier.width(140.dp),
                )
                Button(onClick = { vm.evaluate(evalExpr, evalFrame.ifBlank { null }) }) {
                    Text(Strings.t("cdp_run"))
                }
            }
            // Eval result
            evalResult?.let { r ->
                val text = r.value ?: r.exception ?: ""
                SelectableText(
                    text = "${Strings.t("cdp_result")}: $text",
                    style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // ---- Response body modal ----
    responseModalFor?.let { requestId ->
        AlertDialog(
            onDismissRequest = { responseModalFor = null },
            title = { Text(Strings.t("cdp_response_body")) },
            text = {
                Box(Modifier.fillMaxWidth()) {
                    val body = responseBody
                    if (body == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        SelectableText(
                            text = body,
                            style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { responseModalFor = null }) { Text(Strings.t("ok")) }
            },
        )
    }
}

@Composable
private fun CdpTopBar(
    state: CdpConnectionState,
    targets: List<CdpTarget>,
    selectedTargetId: String?,
    onTargetsOpen: () -> Unit,
    targetsMenuOpen: Boolean,
    onTargetsDismiss: () -> Unit,
    onSelectTarget: (String) -> Unit,
    manualPort: String,
    onManualPortChange: (String) -> Unit,
    onStart: () -> Unit,
    onConnect: () -> Unit,
    onReload: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // State dot
        Box(Modifier.size(10.dp).clip(CircleShape).background(cdpStateColor(state)))
        Text(stateLabel(state), style = MaterialTheme.typography.caption)
        Spacer(Modifier.width(8.dp))

        // Targets dropdown
        Box {
            OutlinedButton(onClick = onTargetsOpen) {
                Text("${Strings.t("cdp_targets")} (${targets.size})")
            }
            DropdownMenu(expanded = targetsMenuOpen, onDismissRequest = onTargetsDismiss) {
                if (targets.isEmpty()) {
                    DropdownMenuItem(onClick = {}) { Text("—") }
                } else {
                    targets.forEach { t ->
                        DropdownMenuItem(onClick = { onSelectTarget(t.targetId) }) {
                            Text(
                                if (t.title.isNotBlank()) "${t.title} (${t.type})" else "${t.url} (${t.type})",
                                fontWeight = if (t.targetId == selectedTargetId) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(onClick = onStart, enabled = state == CdpConnectionState.DISCONNECTED || state == CdpConnectionState.FAILED) {
            Text(Strings.t("cdp_start"))
        }
        OutlinedTextField(
            value = manualPort,
            onValueChange = onManualPortChange,
            singleLine = true,
            placeholder = { Text(Strings.t("cdp_manual_port")) },
            modifier = Modifier.width(100.dp),
        )
        Button(onClick = onConnect) { Text(Strings.t("cdp_connect")) }
        TextButton(onClick = onReload, enabled = state == CdpConnectionState.CONNECTED) {
            Text(Strings.t("cdp_reload"))
        }
    }
}

@Composable
private fun NetworkRow(req: CdpNetworkRequest, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(req.method, style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.width(50.dp))
        Text(
            req.url,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            (req.status?.toString() ?: "—"),
            style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(40.dp),
            color = if (req.state == com.adbgui.core.domain.CdpNetState.FAILED) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
        )
        Text(req.mime ?: "—", style = MaterialTheme.typography.caption, modifier = Modifier.width(80.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun cdpLevelColor(level: CdpLevel): Color {
    val c = AppColors.current
    return when (level) {
        CdpLevel.LOG, CdpLevel.INFO -> c.logInfo
        CdpLevel.DEBUG -> c.logDebug
        CdpLevel.WARNING -> c.logWarn
        CdpLevel.ERROR -> c.logError
    }
}

@Composable
private fun cdpStateColor(state: CdpConnectionState): Color {
    return when (state) {
        CdpConnectionState.DISCONNECTED -> MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
        CdpConnectionState.CONNECTING -> MaterialTheme.colors.secondary
        CdpConnectionState.CONNECTED -> Color(0xFF4CAF50)
        CdpConnectionState.RECONNECTING -> MaterialTheme.colors.secondary
        CdpConnectionState.FAILED -> MaterialTheme.colors.error
    }
}

private fun stateLabel(state: CdpConnectionState): String = when (state) {
    CdpConnectionState.DISCONNECTED -> "—"
    CdpConnectionState.CONNECTING -> "…"
    CdpConnectionState.CONNECTED -> "OK"
    CdpConnectionState.RECONNECTING -> "…"
    CdpConnectionState.FAILED -> "!"
}
