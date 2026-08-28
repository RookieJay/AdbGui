package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.RebootMode
import com.adbgui.desktop.platform.ScrcpyInstaller
import com.adbgui.desktop.platform.ScrcpyLauncher
import com.adbgui.desktop.platform.WindowsScrcpyLocator
import com.adbgui.desktop.ui.i18n.Strings

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
    // Page uses `background` (a step below `surface`) so the SectionCards pop; in dark mode the
    // cards (surface) sit above a darker field instead of blending into it.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Device info ---
            SectionCard(headerTitle = Strings.t("device_info")) {
                DeviceInfoScreen(
                    vm = deviceInfoVm,
                    modifier = Modifier.fillMaxWidth(),
                    onOpenScreenshot = onOpenScreenshot,
                    screenshotLoading = screenshotLoading,
                )
            }
            // --- Device tools: root / remount / shell / reboot ---
            if (systemOpsVm != null) {
                val opsBusy by systemOpsVm.busy.collectAsState()
                val opsMessage by systemOpsVm.message.collectAsState()
                val opsError by systemOpsVm.error.collectAsState()
                var rebootMenuOpen by remember { mutableStateOf(false) }
                var pendingReboot by remember { mutableStateOf<RebootMode?>(null) }
                SectionCard(headerTitle = Strings.t("device_tools")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !opsBusy, onClick = { systemOpsVm.root() }) { Text(Strings.t("root_op")) }
                        OutlinedButton(enabled = !opsBusy, onClick = { systemOpsVm.remount() }) { Text(Strings.t("remount_op")) }
                        OutlinedButton(enabled = selectedSerial != null, onClick = { selectedSerial?.let { onOpenShell(it) } }) { Text(Strings.t("open_shell")) }
                        androidx.compose.foundation.layout.Box {
                            OutlinedButton(enabled = !opsBusy, onClick = { rebootMenuOpen = true }) { Text(Strings.t("reboot")) }
                            androidx.compose.material.DropdownMenu(expanded = rebootMenuOpen, onDismissRequest = { rebootMenuOpen = false }) {
                                RebootMode.entries.forEach { mode ->
                                    androidx.compose.material.DropdownMenuItem(onClick = { rebootMenuOpen = false; pendingReboot = mode }) {
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
                                DangerButton(onClick = { pendingReboot = null; systemOpsVm.reboot(mode) }) { Text(Strings.t("reboot")) }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingReboot = null }) { Text(Strings.t("cancel")) }
                            },
                        )
                    }
                }
            }
            // --- Remote + scrcpy: side-by-side when there's room (fills the empty space beside
            // the D-pad so the user doesn't scroll down to Start scrcpy), stacked when narrow. ---
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val remoteCard = @Composable {
                    SectionCard(headerTitle = Strings.t("remote")) {
                        RemoteScreen(vm = remoteVm, selectedSerial = selectedSerial, modifier = Modifier.fillMaxWidth())
                    }
                }
                val scrcpyCard = @Composable {
                    SectionCard(headerTitle = Strings.t("scrcpy")) {
                        ScrcpySection(
                            selectedSerial = selectedSerial,
                            scrcpyInstaller = scrcpyInstaller,
                            scrcpyLauncher = scrcpyLauncher,
                            settingsVm = settingsVm,
                        )
                    }
                }
                if (maxWidth >= 760.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) { remoteCard() }
                        Box(Modifier.weight(1f)) { scrcpyCard() }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        remoteCard()
                        scrcpyCard()
                    }
                }
            }
        }
    }
}

private fun rebootLabel(mode: RebootMode): String = when (mode) {
    RebootMode.NORMAL -> Strings.t("reboot_normal")
    RebootMode.RECOVERY -> Strings.t("reboot_recovery")
    RebootMode.BOOTLOADER -> Strings.t("reboot_bootloader")
    RebootMode.SIDELOAD -> Strings.t("reboot_sideload")
}
