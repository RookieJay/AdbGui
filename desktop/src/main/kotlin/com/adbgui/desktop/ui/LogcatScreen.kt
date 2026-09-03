package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.adbgui.core.device.LogcatFilters
import com.adbgui.core.device.LogcatStatus
import com.adbgui.core.domain.LogcatLevel
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.theme.AppColors
import kotlinx.coroutines.launch
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
    val fixing by vm.fixing.collectAsState()
    val fixError by vm.fixError.collectAsState()
    // Filter UI state is LOCAL (synchronous) — vm.setFilters is async on serialDispatcher
    // (the L4 concurrency fix), so binding inputs to the controller's StateFlow made checkboxes
    // not uncheck and textfields not type. Local state drives the inputs; setFilters applies async.
    var levelSet by remember { mutableStateOf(LogcatLevel.entries.toSet()) }
    var text by remember { mutableStateOf("") }
    var levelMenuOpen by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val matchHighlight = MaterialTheme.colors.primary.copy(alpha = 0.28f)

    Surface(modifier = modifier.fillMaxSize().onPreviewKeyEvent { e ->
        // Ctrl+F focuses the search box (browser-style find). Fires when focus is anywhere in Logcat.
        if (e.key == Key.F && e.isCtrlPressed) {
            searchFocus.requestFocus()
            true
        } else false
    }, color = MaterialTheme.colors.surface) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                OutlinedTextField(
                    value = text, singleLine = true,
                    onValueChange = {
                        text = it
                        vm.setFilters(LogcatFilters(levelSet = levelSet, text = it.ifBlank { null }))
                    },
                    label = { Text(Strings.t("text_search")) },
                    modifier = Modifier.width(220.dp).focusRequester(searchFocus),
                )
                Spacer(Modifier.weight(1f))
                val paused = status == LogcatStatus.PAUSED
                IconButton(onClick = { if (paused) vm.resume() else vm.pause() }) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = Strings.t(if (paused) "resume" else "pause"),
                    )
                }
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = Strings.t("clear"))
                }
                IconButton(onClick = {
                    val sel = StringSelection(lines.joinToString("\n") { it.raw })
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                }) { Icon(Icons.Filled.ContentCopy, contentDescription = Strings.t("copy")) }
                Button(onClick = {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val target = com.adbgui.desktop.platform.FileDialogs.saveFile(
                        title = Strings.t("save_logcat_title"),
                        defaultName = "logcat_$stamp.txt",
                    )
                    if (target != null) {
                        val t = File(target)
                        runCatching { t.writeText(vm.export()) }
                            .onSuccess { savedFile = t; exportError = null }
                            .onFailure { exportError = Strings.t("status_save_failed").format(it.message) }
                    }
                }) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(Strings.t("export"))
                }
            }

            if (status == LogcatStatus.RECONNECTING || status == LogcatStatus.FAILED) {
                Surface(color = MaterialTheme.colors.background, modifier = Modifier.fillMaxWidth()) {
                    Text(Strings.t("reconnecting_logcat"), Modifier.padding(6.dp), style = MaterialTheme.typography.caption)
                }
            }
            error?.let { e -> InlineMessageBanner(e, MessageKind.Error) }
            savedFile?.let { f ->
                SavedFileBanner(path = f.absolutePath, onOpen = { openFile(f) }, onReveal = { revealFile(f) })
            }

            exportError?.let { msg -> InlineMessageBanner(msg, MessageKind.Error) }

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
            val query = text
            // Empty-state hint: stream is RUNNING but no lines arrived for a grace period (~3s)
            // → some devices (e.g. TCL TVs) ship with logd silenced. Don't flash on fresh start.
            var showEmptyHint by remember { mutableStateOf(false) }
            LaunchedEffect(status, lines.size) {
                showEmptyHint = false
                if (status == LogcatStatus.RUNNING && lines.isEmpty()) {
                    kotlinx.coroutines.delay(3000)
                    // Re-check after the delay — lines may have arrived, or status may have changed.
                    if (status == LogcatStatus.RUNNING && lines.isEmpty()) showEmptyHint = true
                }
            }
            Box(Modifier.fillMaxSize()) {
                // SelectionContainer makes the log lines selectable + copyable (Ctrl+C); without it
                // plain Text can't be selected, which made copying a single line impossible.
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                highlightMatches(line.raw, query, matchHighlight),
                                color = levelColor(line.level),
                                style = MaterialTheme.typography.body2,
                            )
                        }
                    }
                }
                // Floating "jump to latest" button when the user has scrolled up.
                if (!userAtBottom && lines.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { scrollScope.launch { listState.animateScrollToItem(lines.size - 1) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = Strings.t("scroll_to_latest")) }
                }
                // Empty-state "fix logcat" overlay (logd silenced, e.g. TCL TVs).
                if (showEmptyHint) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(Strings.t("logcat_empty_hint"), style = MaterialTheme.typography.body2)
                        Button(onClick = { vm.fixLogcat() }, enabled = !fixing) {
                            Text(Strings.t(if (fixing) "logcat_fix_running" else "logcat_fix_button"))
                        }
                        fixError?.let { InlineMessageBanner(it, MessageKind.Error) }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(Strings.t("clear_logcat_confirm_title")) },
            text = { Text(Strings.t("clear_logcat_confirm_body")) },
            confirmButton = {
                DangerButton(onClick = { vm.clear(); confirmClear = false }) { Text(Strings.t("clear")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(Strings.t("cancel")) }
            },
        )
    }
}

@Composable
private fun levelColor(l: LogcatLevel): Color {
    val c = AppColors.current
    return when (l) {
        LogcatLevel.V -> c.logVerbose
        LogcatLevel.D -> c.logDebug
        LogcatLevel.I -> c.logInfo
        LogcatLevel.W -> c.logWarn
        LogcatLevel.E, LogcatLevel.F -> c.logError
    }
}

/**
 * Renders [raw] with every case-insensitive occurrence of [query] given a [highlight] background,
 * so the user can see *why* a line matched the filter. Used per visible line (the filter already
 * hides non-matching lines, so every visible line contains the query at least once).
 */
private fun highlightMatches(raw: String, query: String, highlight: Color): AnnotatedString =
    buildAnnotatedString {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) { append(raw); return@buildAnnotatedString }
        val hay = raw.lowercase()
        var i = 0
        while (i < raw.length) {
            val idx = hay.indexOf(needle, i)
            if (idx < 0) { append(raw.substring(i)); break }
            append(raw.substring(i, idx))
            withStyle(SpanStyle(background = highlight)) {
                append(raw.substring(idx, idx + needle.length))
            }
            i = idx + needle.length
        }
        if (i < raw.length) append(raw.substring(i))
    }

private fun levelLabel(levelSet: Set<LogcatLevel>): String =
    if (levelSet.size == LogcatLevel.entries.size) Strings.t("level_all")
    else levelSet.joinToString(",") { it.name }
