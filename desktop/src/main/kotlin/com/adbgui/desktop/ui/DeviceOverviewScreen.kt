package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.RebootMode
import com.adbgui.core.domain.ScrcpyMode
import com.adbgui.core.domain.ScrcpyOptions
import com.adbgui.core.domain.ScrcpyLaunchProfile
import com.adbgui.desktop.platform.ScrcpyInstaller
import com.adbgui.desktop.platform.ScrcpyLauncher
import com.adbgui.desktop.platform.WindowsScrcpyLocator
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun DeviceOverviewScreen(
    deviceInfoVm: DeviceInfoViewModel,
    remoteVm: RemoteViewModel,
    onOpenScreenshot: () -> Unit,
    screenshotLoading: Boolean,
    selectedSerial: String?,
    scrcpyInstaller: ScrcpyInstaller,
    scrcpyLocator: WindowsScrcpyLocator,
    scrcpyLauncher: ScrcpyLauncher,
    settingsVm: SettingsViewModel? = null,
    systemOpsVm: SystemOpsViewModel? = null,
    onOpenShell: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Strings.t("device_overview"), style = MaterialTheme.typography.h5)
            // --- Device info (full width). Screenshot button lives in its toolbar;
            // opens an independent window. ---
            DeviceInfoScreen(
                vm = deviceInfoVm,
                modifier = Modifier.fillMaxWidth(),
                onOpenScreenshot = onOpenScreenshot,
                screenshotLoading = screenshotLoading,
            )
            // --- Device tools (top): root / remount / shell / reboot ---
            if (systemOpsVm != null) {
                val opsBusy by systemOpsVm.busy.collectAsState()
                val opsMessage by systemOpsVm.message.collectAsState()
                val opsError by systemOpsVm.error.collectAsState()
                var rebootMenuOpen by remember { mutableStateOf(false) }
                var pendingReboot by remember { mutableStateOf<RebootMode?>(null) }
                Text(Strings.t("device_tools"), style = MaterialTheme.typography.subtitle1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !opsBusy, onClick = { systemOpsVm.root() }) { Text(Strings.t("root_op")) }
                    OutlinedButton(enabled = !opsBusy, onClick = { systemOpsVm.remount() }) { Text(Strings.t("remount_op")) }
                    OutlinedButton(enabled = selectedSerial != null, onClick = { selectedSerial?.let { onOpenShell(it) } }) { Text(Strings.t("open_shell")) }
                    Box {
                        OutlinedButton(enabled = !opsBusy, onClick = { rebootMenuOpen = true }) { Text(Strings.t("reboot")) }
                        DropdownMenu(expanded = rebootMenuOpen, onDismissRequest = { rebootMenuOpen = false }) {
                            RebootMode.entries.forEach { mode ->
                                DropdownMenuItem(onClick = { rebootMenuOpen = false; pendingReboot = mode }) {
                                    Text(rebootLabel(mode))
                                }
                            }
                        }
                    }
                }
                opsMessage?.let { msg -> InlineMessageBanner(msg.trim(), MessageKind.Success) }
                opsError?.let { msg -> InlineMessageBanner(msg, MessageKind.Error) }
                pendingReboot?.let { mode ->
                    AlertDialog(
                        onDismissRequest = { pendingReboot = null },
                        title = { Text(Strings.t("reboot_confirm_title")) },
                        text = { Text(Strings.t("reboot_confirm_body").format(rebootLabel(mode))) },
                        confirmButton = {
                            TextButton(onClick = { pendingReboot = null; systemOpsVm.reboot(mode) }) { Text(Strings.t("reboot")) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingReboot = null }) { Text(Strings.t("cancel")) }
                        },
                    )
                }
            }
            Divider()
            // --- Remote (full width) ---
            RemoteScreen(vm = remoteVm, selectedSerial = selectedSerial, modifier = Modifier.fillMaxWidth())
            Divider()
            // --- scrcpy section ---
            Text(Strings.t("scrcpy"), style = MaterialTheme.typography.subtitle1)
            val scrcpyPath = remember { mutableStateOf<String?>(null) }
            val scrcpyStatus = remember { mutableStateOf("installing") } // installing | installed | failed
            val scrcpyRunning = remember { mutableStateOf(false) }
            val scrcpyError = remember { mutableStateOf<String?>(null) }
            // Path of the most recent recording (set at Start, kept after Stop so the user can
            // locate the file). Cleared when a new投屏 starts without recording.
            val lastRecordFile = remember { mutableStateOf<String?>(null) }
            // Launch options (session state). Defaults mirror ScrcpyOptions defaults.
            val optStayAwake = remember { mutableStateOf(ScrcpyOptions().stayAwake) }
            val optTurnScreenOff = remember { mutableStateOf(ScrcpyOptions().turnScreenOff) }
            val optAlwaysOnTop = remember { mutableStateOf(ScrcpyOptions().alwaysOnTop) }
            val optFullscreen = remember { mutableStateOf(ScrcpyOptions().fullscreen) }
            val optNoAudio = remember { mutableStateOf(ScrcpyOptions().noAudio) }
            val optRecord = remember { mutableStateOf(false) }
            val optMaxSize = remember { mutableStateOf(ScrcpyOptions().maxSize.toString()) }
            val optMaxFps = remember { mutableStateOf(ScrcpyOptions().maxFps.toString()) }
            val optRecordPath = remember { mutableStateOf(ScrcpyOptions().recordPath.orEmpty()) }
            // Seed opt states from the persisted profile (re-applied when scrcpyLaunch changes —
            // i.e. on initial async load and after each save; on save the values are what the user
            // just launched, so re-apply is a no-op).
            val settingsState = settingsVm?.settings?.collectAsState()?.value
            LaunchedEffect(settingsState?.scrcpyLaunch) {
                val p = settingsState?.scrcpyLaunch ?: return@LaunchedEffect
                optMaxSize.value = p.maxSize.toString()
                optMaxFps.value = p.maxFps.toString()
                optStayAwake.value = p.stayAwake
                optTurnScreenOff.value = p.turnScreenOff
                optAlwaysOnTop.value = p.alwaysOnTop
                optFullscreen.value = p.fullscreen
                optNoAudio.value = p.noAudio
                optRecord.value = !p.recordFolder.isNullOrBlank()
                optRecordPath.value = p.recordFolder ?: ""
            }
            val showShortcuts = remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                try {
                    scrcpyPath.value = scrcpyInstaller.ensureInstalled()
                    scrcpyStatus.value = "installed"
                } catch (e: Exception) {
                    scrcpyStatus.value = "failed"
                    scrcpyError.value = e.message
                }
            }

            when (scrcpyStatus.value) {
                "installed" -> {
                    Text(Strings.t("scrcpy_status_installed"))
                    // Launch/run errors surface here (top of section) so they're not buried
                    // below the buttons in black text.
                    scrcpyError.value?.let { msg ->
                        InlineMessageBanner(msg, MessageKind.Error)
                    }
                    // Hoisted so the Start button (above options) can gate on them too.
                    val maxSizeN = optMaxSize.value.toIntOrNull()
                    val maxFpsN = optMaxFps.value.toIntOrNull()
                    val maxSizeErr = maxSizeN != null && maxSizeN != 0 && (maxSizeN < 16 || maxSizeN > 8192)
                    val maxFpsErr = maxFpsN != null && maxFpsN != 0 && (maxFpsN < 1 || maxFpsN > 120)
                    // --- Buttons row (placed above options so Start is immediately visible) ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = selectedSerial != null && !scrcpyRunning.value && !maxSizeErr && !maxFpsErr,
                            onClick = {
                                val path = scrcpyPath.value ?: return@Button
                                val serial = selectedSerial ?: return@Button
                                val recordPath = if (optRecord.value) {
                                    optRecordPath.value.ifBlank { null }?.let {
                                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                                        File(it, "scrcpy_$stamp.mp4").absolutePath
                                    }
                                } else null
                                lastRecordFile.value = recordPath
                                val options = ScrcpyOptions(
                                    maxSize = optMaxSize.value.toIntOrNull() ?: 0,
                                    stayAwake = optStayAwake.value,
                                    turnScreenOff = optTurnScreenOff.value,
                                    recordPath = recordPath,
                                    alwaysOnTop = optAlwaysOnTop.value,
                                    fullscreen = optFullscreen.value,
                                    maxFps = optMaxFps.value.toIntOrNull() ?: 0,
                                    noAudio = optNoAudio.value,
                                )
                                settingsVm?.setScrcpyLaunch(
                                    ScrcpyLaunchProfile(
                                        maxSize = optMaxSize.value.toIntOrNull() ?: 0,
                                        stayAwake = optStayAwake.value,
                                        turnScreenOff = optTurnScreenOff.value,
                                        alwaysOnTop = optAlwaysOnTop.value,
                                        fullscreen = optFullscreen.value,
                                        maxFps = optMaxFps.value.toIntOrNull() ?: 0,
                                        noAudio = optNoAudio.value,
                                        recordFolder = if (optRecord.value) optRecordPath.value.ifBlank { null } else null,
                                    )
                                )
                                scrcpyRunning.value = true
                                scrcpyError.value = null
                                scope.launch {
                                    try {
                                        scrcpyLauncher.open(path, serial, options, ScrcpyMode.EXTERNAL) { code, out ->
                                            // scrcpy exited on its own (window closed / failed) — clear running,
                                            // and surface failures (non-zero) so the user sees why (e.g. encoder err).
                                            scope.launch {
                                                scrcpyRunning.value = false
                                                if (code != 0) {
                                                    scrcpyError.value = buildString {
                                                        append("scrcpy exited (code $code)")
                                                        if (out.isNotBlank()) {
                                                            append('\n')
                                                            // Trim to a useful tail — scrcpy errors are usually a few lines.
                                                            append(out.takeLast(500))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        scrcpyError.value = e.message
                                        scrcpyRunning.value = false
                                    }
                                }
                            },
                        ) { Text(Strings.t("start_scrcpy")) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = scrcpyRunning.value,
                            onClick = {
                                scrcpyLauncher.stop()
                                scrcpyRunning.value = false
                            },
                        ) { Text(Strings.t("stop_scrcpy")) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { showShortcuts.value = true }) {
                            Text(Strings.t("scrcpy_shortcuts"))
                        }
                    }
                    // Mode toggle. EXTERNAL works; EMBEDDED (JNA reparent) is reserved —
                    // disabled pending implementation, slot kept so users know it's planned.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = true, onClick = {}, enabled = false)
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_mode_external"))
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = false, onClick = {}, enabled = false)
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_mode_embedded") + " " + Strings.t("scrcpy_mode_wip"))
                    }
                    // --- Launch options panel ---
                    // Checkbox row 1
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optAlwaysOnTop.value, onCheckedChange = { optAlwaysOnTop.value = it })
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_always_on_top"))
                        Spacer(Modifier.width(16.dp))
                        Checkbox(checked = optFullscreen.value, onCheckedChange = { optFullscreen.value = it })
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_fullscreen"))
                    }
                    // Checkbox row 2
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optStayAwake.value, onCheckedChange = { optStayAwake.value = it })
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_stay_awake"))
                        Spacer(Modifier.width(16.dp))
                        Checkbox(checked = optTurnScreenOff.value, onCheckedChange = { optTurnScreenOff.value = it })
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_turn_screen_off"))
                        Spacer(Modifier.width(16.dp))
                        Checkbox(checked = optNoAudio.value, onCheckedChange = { optNoAudio.value = it })
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_no_audio"))
                    }
                    // Numeric + record fields
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = optMaxSize.value,
                            onValueChange = { optMaxSize.value = it.filter { c -> c.isDigit() } },
                            label = { Text(Strings.t("scrcpy_max_size")) },
                            singleLine = true,
                            isError = maxSizeErr,
                            modifier = Modifier.width(140.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = optMaxFps.value,
                            onValueChange = { optMaxFps.value = it.filter { c -> c.isDigit() } },
                            label = { Text(Strings.t("scrcpy_max_fps")) },
                            singleLine = true,
                            isError = maxFpsErr,
                            modifier = Modifier.width(140.dp),
                        )
                    }
                    if (maxSizeErr || maxFpsErr) {
                        Text(
                            if (maxSizeErr) Strings.t("scrcpy_max_size_err") else Strings.t("scrcpy_max_fps_err"),
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.error,
                        )
                    }
                    // Recording is explicit: only when checked do we pass --record.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optRecord.value, onCheckedChange = { optRecord.value = it })
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_record_toggle"))
                    }
                    if (optRecord.value) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = optRecordPath.value,
                                onValueChange = { optRecordPath.value = it },
                                label = { Text(Strings.t("scrcpy_record")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val chosen = com.adbgui.desktop.platform.FileDialogs.pickDirectory(
                                    title = Strings.t("scrcpy_record"),
                                    currentPath = optRecordPath.value,
                                )
                                if (chosen != null) optRecordPath.value = chosen
                            }) { Text(Strings.t("browse")) }
                        }
                    }
                    // After stopping, show the recorded file's path with open/reveal links.
                    lastRecordFile.value?.let { path ->
                        val f = File(path)
                        SavedFileBanner(path = path, onOpen = { openFile(f) }, onReveal = { revealFile(f) })
                    }
                    // --- Shortcuts dialog ---
                    if (showShortcuts.value) {
                        ScrcpyShortcutsDialog(onDismiss = { showShortcuts.value = false })
                    }
                }
                "installing" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(Strings.t("scrcpy_status_installing"))
                }
                "failed" -> {
                    Text(Strings.t("scrcpy_status_failed"))
                    scrcpyError.value?.let { SelectableText(it) }
                    Button(onClick = {
                        scrcpyStatus.value = "installing"
                        scope.launch {
                            try {
                                scrcpyPath.value = scrcpyInstaller.ensureInstalled()
                                scrcpyStatus.value = "installed"
                            } catch (e: Exception) {
                                scrcpyStatus.value = "failed"
                                scrcpyError.value = e.message
                            }
                        }
                    }) { Text(Strings.t("scrcpy_retry")) }
                }
            }
        }
    }
}

/**
 * Curated scrcpy MOD+key shortcuts (from `scrcpy.exe --help`, v4.1).
 * Each pair: (key combination shown to user, i18n key for the description).
 * MOD = Left Alt by default. Kept to ~12 of the most useful rows.
 */
private val scrcpyShortcuts: List<Pair<String, String>> = listOf(
    "MOD+b" to "scrcpy_sc_back",
    "MOD+h" to "scrcpy_sc_home",
    "MOD+s" to "scrcpy_sc_appswitch",
    "MOD+n" to "scrcpy_sc_notifications",
    "MOD+Shift+n" to "scrcpy_sc_quicksettings",
    "MOD+o" to "scrcpy_sc_screenoff",
    "MOD+f" to "scrcpy_sc_fullscreen",
    "MOD+m" to "scrcpy_sc_alwaysontop",
    "MOD+i" to "scrcpy_sc_fps",
    "MOD+p" to "scrcpy_sc_power",
    "MOD+↑/↓" to "scrcpy_sc_volume",
    "MOD+c/v/x" to "scrcpy_sc_clipboard",
    "右键 / Right-click" to "scrcpy_sc_rightclick_back",
    "中键 / Middle-click" to "scrcpy_sc_midclick_home",
)

@Composable
private fun ScrcpyShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t("scrcpy_shortcuts")) },
        text = {
            Column {
                Text(Strings.t("scrcpy_shortcuts_hint"))
                Spacer(Modifier.height(8.dp))
                scrcpyShortcuts.forEach { (key, descKey) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(key, style = MaterialTheme.typography.body2)
                        Text(Strings.t(descKey), style = MaterialTheme.typography.body2)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(Strings.t("ok")) }
        },
    )
}

private fun rebootLabel(mode: RebootMode): String = when (mode) {
    RebootMode.NORMAL -> Strings.t("reboot_normal")
    RebootMode.RECOVERY -> Strings.t("reboot_recovery")
    RebootMode.BOOTLOADER -> Strings.t("reboot_bootloader")
    RebootMode.SIDELOAD -> Strings.t("reboot_sideload")
}
