package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
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
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path

@Composable
fun AppShell(
    vm: DeviceListViewModel,
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel? = null,
    configDir: Path? = null,
    deviceOverviewDeviceInfoVm: DeviceInfoViewModel? = null,
    deviceOverviewRemoteVm: RemoteViewModel? = null,
    onOpenScreenshot: () -> Unit = {},
    screenshotLoading: Boolean = false,
    scrcpyInstaller: com.adbgui.desktop.platform.ScrcpyInstaller? = null,
    scrcpyLocator: com.adbgui.desktop.platform.WindowsScrcpyLocator? = null,
    scrcpyLauncher: com.adbgui.desktop.platform.ScrcpyLauncher? = null,
    appConsoleVm: AppConsoleViewModel? = null,
    logcatVm: LogcatViewModel? = null,
    systemOpsVm: SystemOpsViewModel? = null,
    systemInfoVm: SystemInfoViewModel? = null,
    fileExplorerVm: FileExplorerViewModel? = null,
    selectedSerial: MutableStateFlow<String?>? = null,
    onOpenShell: (String) -> Unit = {},
    repo: com.adbgui.core.device.DeviceRepository? = null,
    adbLocator: com.adbgui.core.adb.AdbLocator? = null,
    rightContent: @Composable () -> Unit = { DefaultRightPane() },
) {
    var showSettings by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(NavPage.DEVICE_OVERVIEW) }
    val serialFlow = selectedSerial ?: remember { MutableStateFlow<String?>(null) }
    val selected by serialFlow.collectAsState()

    MaterialTheme {
        Row(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(280.dp).fillMaxHeight()) {
                DeviceListPane(
                    vm = vm,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    selected = selected,
                    onSelect = { device -> selectedSerial?.value = device.serial },
                    onReconnect = { ip, port -> vm.reconnect(ip, port) },
                )
                Divider()
                if (deviceOverviewDeviceInfoVm != null && deviceOverviewRemoteVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.DEVICE_OVERVIEW },
                    ) { Text(if (page == NavPage.DEVICE_OVERVIEW && !showSettings) "${Strings.t("nav_device_overview")} *" else Strings.t("nav_device_overview")) }
                }
                if (appConsoleVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.APP_CONSOLE },
                    ) { Text(if (page == NavPage.APP_CONSOLE && !showSettings) "${Strings.t("nav_app_console")} *" else Strings.t("nav_app_console")) }
                }
                if (logcatVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.LOGCAT },
                    ) { Text(if (page == NavPage.LOGCAT && !showSettings) "${Strings.t("nav_logcat")} *" else Strings.t("nav_logcat")) }
                }
                if (systemOpsVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.SYSTEM_OPS },
                    ) { Text(if (page == NavPage.SYSTEM_OPS && !showSettings) "${Strings.t("nav_system_ops")} *" else Strings.t("nav_system_ops")) }
                }
                if (systemInfoVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.SYSTEM_INFO },
                    ) { Text(if (page == NavPage.SYSTEM_INFO && !showSettings) "${Strings.t("nav_system_info")} *" else Strings.t("nav_system_info")) }
                }
                if (fileExplorerVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.FILE_EXPLORER },
                    ) { Text(if (page == NavPage.FILE_EXPLORER && !showSettings) "${Strings.t("nav_file_explorer")} *" else Strings.t("nav_file_explorer")) }
                }
                if (settingsVm != null && configDir != null) {
                    Divider()
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = !showSettings },
                    ) {
                        Text(if (showSettings) Strings.t("nav_back_to_devices") else Strings.t("nav_settings"))
                    }
                }
            }
            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
            Surface(modifier = Modifier.fillMaxSize()) {
                when {
                    showSettings && settingsVm != null && configDir != null -> {
                        SettingsScreen(vm = settingsVm, configDir = configDir, scrcpyLocator = scrcpyLocator, repo = repo, adbLocator = adbLocator)
                    }
                    selected != null && page == NavPage.DEVICE_OVERVIEW && deviceOverviewDeviceInfoVm != null && deviceOverviewRemoteVm != null && scrcpyInstaller != null && scrcpyLocator != null && scrcpyLauncher != null -> {
                        DeviceOverviewScreen(
                            deviceInfoVm = deviceOverviewDeviceInfoVm,
                            remoteVm = deviceOverviewRemoteVm,
                            onOpenScreenshot = onOpenScreenshot,
                            screenshotLoading = screenshotLoading,
                            selectedSerial = selected,
                            scrcpyInstaller = scrcpyInstaller,
                            scrcpyLocator = scrcpyLocator,
                            scrcpyLauncher = scrcpyLauncher,
                            settingsVm = settingsVm,
                            systemOpsVm = systemOpsVm,
                            onOpenShell = onOpenShell,
                        )
                    }
                    selected != null && page == NavPage.APP_CONSOLE && appConsoleVm != null -> {
                        AppConsoleScreen(vm = appConsoleVm, selectedSerial = selected)
                    }
                    selected != null && page == NavPage.LOGCAT && logcatVm != null -> {
                        LogcatScreen(vm = logcatVm)
                    }
                    selected != null && page == NavPage.SYSTEM_OPS && systemOpsVm != null -> {
                        SystemOpsScreen(vm = systemOpsVm, selectedSerial = selected)
                    }
                    selected != null && page == NavPage.SYSTEM_INFO && systemInfoVm != null -> {
                        SystemInfoScreen(vm = systemInfoVm, selectedSerial = selected)
                    }
                    selected != null && page == NavPage.FILE_EXPLORER && fileExplorerVm != null -> {
                        FileExplorerScreen(vm = fileExplorerVm, selectedSerial = selected)
                    }
                    else -> rightContent()
                }
            }
        }
    }
}

private enum class NavPage { DEVICE_OVERVIEW, APP_CONSOLE, LOGCAT, SYSTEM_OPS, SYSTEM_INFO, FILE_EXPLORER }

@Composable
private fun DefaultRightPane() {
    Text(Strings.t("no_device_selected"))
}
