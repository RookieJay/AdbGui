package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.ScrcpyMode
import com.adbgui.core.domain.ScrcpyOptions
import com.adbgui.desktop.platform.ScrcpyInstaller
import com.adbgui.desktop.platform.ScrcpyLauncher
import com.adbgui.desktop.platform.WindowsScrcpyLocator
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.launch

@Composable
fun DeviceOverviewScreen(
    deviceInfoVm: DeviceInfoViewModel,
    screenshotVm: ScreenshotViewModel,
    remoteVm: RemoteViewModel,
    selectedSerial: String?,
    scrcpyInstaller: ScrcpyInstaller,
    scrcpyLocator: WindowsScrcpyLocator,
    scrcpyLauncher: ScrcpyLauncher,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Strings.t("device_overview"), style = MaterialTheme.typography.h5)
            // --- Top row: device info (left) + screenshot (right) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DeviceInfoScreen(vm = deviceInfoVm, modifier = Modifier.weight(1f))
                ScreenshotScreen(vm = screenshotVm, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            // --- Remote (full width below) ---
            RemoteScreen(vm = remoteVm, selectedSerial = selectedSerial, modifier = Modifier.fillMaxWidth())
            Divider()
            // --- scrcpy section ---
            Text(Strings.t("scrcpy"), style = MaterialTheme.typography.subtitle1)
            val scrcpyPath = remember { mutableStateOf<String?>(null) }
            val scrcpyStatus = remember { mutableStateOf("installing") } // installing | installed | failed
            val scrcpyRunning = remember { mutableStateOf(false) }
            val scrcpyError = remember { mutableStateOf<String?>(null) }
            // Launch options (session state). Defaults mirror ScrcpyOptions defaults.
            val optStayAwake = remember { mutableStateOf(ScrcpyOptions().stayAwake) }
            val optTurnScreenOff = remember { mutableStateOf(ScrcpyOptions().turnScreenOff) }
            val optAlwaysOnTop = remember { mutableStateOf(ScrcpyOptions().alwaysOnTop) }
            val optFullscreen = remember { mutableStateOf(ScrcpyOptions().fullscreen) }
            val optNoAudio = remember { mutableStateOf(ScrcpyOptions().noAudio) }
            val optMaxSize = remember { mutableStateOf(ScrcpyOptions().maxSize.toString()) }
            val optMaxFps = remember { mutableStateOf(ScrcpyOptions().maxFps.toString()) }
            val optRecordPath = remember { mutableStateOf(ScrcpyOptions().recordPath.orEmpty()) }
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
                            modifier = Modifier.width(140.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = optMaxFps.value,
                            onValueChange = { optMaxFps.value = it.filter { c -> c.isDigit() } },
                            label = { Text(Strings.t("scrcpy_max_fps")) },
                            singleLine = true,
                            modifier = Modifier.width(140.dp),
                        )
                    }
                    OutlinedTextField(
                        value = optRecordPath.value,
                        onValueChange = { optRecordPath.value = it },
                        label = { Text(Strings.t("scrcpy_record")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // --- Buttons row ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = selectedSerial != null && !scrcpyRunning.value,
                            onClick = {
                                val path = scrcpyPath.value ?: return@Button
                                val serial = selectedSerial ?: return@Button
                                val options = ScrcpyOptions(
                                    maxSize = optMaxSize.value.toIntOrNull() ?: 0,
                                    stayAwake = optStayAwake.value,
                                    turnScreenOff = optTurnScreenOff.value,
                                    recordPath = optRecordPath.value.ifBlank { null },
                                    alwaysOnTop = optAlwaysOnTop.value,
                                    fullscreen = optFullscreen.value,
                                    maxFps = optMaxFps.value.toIntOrNull() ?: 0,
                                    noAudio = optNoAudio.value,
                                )
                                scrcpyRunning.value = true
                                scrcpyError.value = null
                                scope.launch {
                                    try {
                                        scrcpyLauncher.open(path, serial, options, ScrcpyMode.EXTERNAL)
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
                    scrcpyError.value?.let { SelectableText(it) }
                    // --- Shortcuts dialog ---
                    if (showShortcuts.value) {
                        ScrcpyShortcutsDialog(onDismiss = { showShortcuts.value = false })
                    }
                }
                "installing" -> Text(Strings.t("scrcpy_status_installing"))
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
