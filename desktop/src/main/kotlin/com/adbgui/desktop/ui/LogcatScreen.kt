package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import com.adbgui.core.device.ManualRecordState
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
    // 页面可见才起流（spec §3）：离开页面不停流，缓冲历史保留。
    LaunchedEffect(Unit) { vm.onPageEntered() }
    val lines by vm.lines.collectAsState()
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val fixing by vm.fixing.collectAsState()
    val fixError by vm.fixError.collectAsState()
    val deviceOnline by vm.deviceOnline.collectAsState()
    // Filter UI state is LOCAL (synchronous) — vm.setFilters is async on serialDispatcher
    // (the L4 concurrency fix), so binding inputs to the controller's StateFlow made checkboxes
    // not uncheck and textfields not type. Local state drives the inputs; setFilters applies async.
    var levelSet by remember { mutableStateOf(LogcatLevel.entries.toSet()) }
    var text by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var levelMenuOpen by remember { mutableStateOf(false) }
    val exporting by vm.exporting.collectAsState()
    val exportProgress by vm.exportProgress.collectAsState()
    val exportError by vm.exportError.collectAsState()
    val savedPath by vm.savedPath.collectAsState()
    val manualRecord by vm.manualRecord.collectAsState()
    val recordError by vm.recordError.collectAsState()
    val autoRecordStates by vm.autoRecordStates.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val matchHighlight = MaterialTheme.colors.primary.copy(alpha = 0.28f)
    val revealScope = rememberCoroutineScope()

    // 手动录制结束（用户停止/断连/写失败）→ 立即在资源管理器中定位并选中该文件；
    // 文件不存在（从未写入）则跳过。
    LaunchedEffect(manualRecord) {
        val st = manualRecord
        if (st is ManualRecordState.Stopped && java.nio.file.Files.exists(st.file)) {
            revealFile(st.file.toFile())
        }
    }

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
                                vm.setFilters(LogcatFilters(levelSet = ns, text = text.ifBlank { null }, textIsRegex = useRegex))
                            }) {
                                Text("${if (lvl in levelSet) "✓" else "•"}  ${lvl.name}")
                            }
                        }
                    }
                }
                // One text input (matches across the raw line: tag + message + timestamp + pid).
                // Fills the toolbar like AS logcat; the `.*` pill toggles regex mode
                // (case-sensitive — embed `(?i)` for insensitive; invalid pattern degrades
                // to plain contains and turns the border red).
                SearchField(
                    value = text,
                    onValueChange = {
                        text = it
                        vm.setFilters(LogcatFilters(levelSet = levelSet, text = it.ifBlank { null }, textIsRegex = useRegex))
                    },
                    placeholder = Strings.t("text_search"),
                    regex = useRegex,
                    onRegexChange = {
                        useRegex = it
                        vm.setFilters(LogcatFilters(levelSet = levelSet, text = text.ifBlank { null }, textIsRegex = it))
                    },
                    modifier = Modifier.weight(1f).focusRequester(searchFocus),
                )
                val paused = status == LogcatStatus.PAUSED
                TooltipIconButton(
                    onClick = { if (paused) vm.resume() else vm.pause() },
                    tooltip = { Strings.t(if (paused) "resume" else "pause") },
                ) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = Strings.t(if (paused) "resume" else "pause"),
                    )
                }
                val recordingNow = manualRecord is ManualRecordState.Recording
                Button(
                    enabled = recordingNow || deviceOnline,
                    colors = if (recordingNow) ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error
                    ) else ButtonDefaults.buttonColors(),
                    onClick = { if (recordingNow) vm.stopRecording() else vm.startRecording() },
                ) {
                    Icon(
                        if (recordingNow) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = Strings.t(if (recordingNow) "record_stop" else "record_start"),
                        tint = if (!recordingNow) MaterialTheme.colors.error else contentColorFor(MaterialTheme.colors.error),
                    )
                    if (recordingNow) {
                        Spacer(Modifier.width(4.dp))
                        val r = manualRecord as ManualRecordState.Recording
                        Text(Strings.t("recording_status").format(r.lineCount, formatBytes(r.bytesWritten)))
                    }
                }
                // 常驻定位按钮：始终可点——有录制文件时定位并选中最新一个（含后台录制，
                // 跨重启有效，点击时实时扫描所以外部删/增文件立刻可见）；目录空时打开录制目录
                TooltipIconButton(
                    onClick = {
                        revealScope.launch {
                            val latest = vm.latestRecordingFile()
                            if (latest != null) revealFile(latest.toFile())
                            else openFile(File(vm.recordingsDir.toAbsolutePath().toString()))
                        }
                    },
                    tooltip = { Strings.t("locate_latest_recording") },
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = Strings.t("locate_latest_recording"))
                }
                TooltipIconButton(
                    onClick = { confirmClear = true },
                    tooltip = { Strings.t("clear") },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = Strings.t("clear"))
                }
                TooltipIconButton(
                    onClick = {
                        val sel = StringSelection(vm.export())
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                    },
                    tooltip = { Strings.t("copy") },
                ) { Icon(Icons.Filled.ContentCopy, contentDescription = Strings.t("copy")) }
                Button(
                    enabled = !exporting,
                    onClick = {
                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        val target = com.adbgui.desktop.platform.FileDialogs.saveFile(
                            title = Strings.t("save_logcat_title"),
                            defaultName = "logcat_$stamp.txt",
                        )
                        if (target != null) {
                            vm.exportFull(File(target).absolutePath)
                        }
                    },
                ) {
                    Icon(Icons.Filled.Download, contentDescription = Strings.t("export"))
                    Spacer(Modifier.width(4.dp))
                    Text(if (exporting) Strings.t("export_progress").format(exportProgress) else Strings.t("export"))
                }
            }

            if (status == LogcatStatus.RECONNECTING || status == LogcatStatus.FAILED) {
                Surface(color = MaterialTheme.colors.background, modifier = Modifier.fillMaxWidth()) {
                    Text(Strings.t("reconnecting_logcat"), Modifier.padding(6.dp), style = MaterialTheme.typography.caption)
                }
            }
            error?.let { e -> InlineMessageBanner(e, MessageKind.Error) }
            recordError?.let { msg -> InlineMessageBanner(msg, MessageKind.Error) }
            when (val mr = manualRecord) {
                is ManualRecordState.Stopped -> {
                    if (mr.reason == "device_disconnected") {
                        InlineMessageBanner(Strings.t("record_stopped_device_disconnected"), MessageKind.Error)
                    } else if (mr.reason == "write_failed") {
                        InlineMessageBanner(Strings.t("record_stopped_write_failed"), MessageKind.Error)
                    } else if (mr.reason == "recording_dir_changed") {
                        InlineMessageBanner(Strings.t("record_stopped_dir_changed"), MessageKind.Success)
                    }
                    SavedFileBanner(
                        path = mr.file.toFile().absolutePath,
                        onOpen = { openFile(mr.file.toFile()) },
                        onReveal = { revealFile(mr.file.toFile()) },
                    )
                }
                else -> {}
            }
            savedPath?.let { p ->
                val f = File(p)
                SavedFileBanner(path = f.absolutePath, onOpen = { openFile(f) }, onReveal = { revealFile(f) })
            }
            exportError?.let { msg -> InlineMessageBanner(msg, MessageKind.Error) }
            if (autoRecordStates.isNotEmpty()) {
                Text(
                    Strings.t("auto_recording"),
                    Modifier.padding(start = 4.dp).clickable { revealFile(File(vm.recordingsDir.toAbsolutePath().toString())) }
                        .padding(4.dp),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary,
                )
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
                // Instant snap (not animate) so a fast/chatty stream on a laggy device can keep up —
                // animateScrollToItem gets cancelled/re-launched on every new line and never settles.
                if (userAtBottom && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
            }
            val scrollScope = rememberCoroutineScope()
            val query = text
            // Empty-state hint: stream is RUNNING but no lines arrived for a grace period (~3s)
            // AND the device is genuinely online → some devices (e.g. TCL TVs) ship with logd
            // silenced. Don't flash on fresh start, and don't show for disconnected devices
            // (which also yield an empty stream — that's not a silenced logd).
            var showEmptyHint by remember { mutableStateOf(false) }
            LaunchedEffect(status, lines.size, deviceOnline) {
                showEmptyHint = false
                if (deviceOnline && status == LogcatStatus.RUNNING && lines.isEmpty()) {
                    kotlinx.coroutines.delay(3000)
                    if (deviceOnline && status == LogcatStatus.RUNNING && lines.isEmpty()) showEmptyHint = true
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
                // Floating "jump to latest" button when the user has scrolled up. Instant snap
                // (animate gets interrupted by new lines on a fast stream and never reaches bottom).
                if (!userAtBottom && lines.isNotEmpty()) {
                    Tooltip(
                        text = { Strings.t("scroll_to_latest") },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        OutlinedButton(onClick = { scrollScope.launch { listState.scrollToItem(lines.size - 1) } }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = Strings.t("scroll_to_latest"))
                        }
                    }
                }
                // Right-edge scrollbar so the user can see position and drag (animateScrollToItem
                // failing left them with no cue where they were on a laggy device).
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
