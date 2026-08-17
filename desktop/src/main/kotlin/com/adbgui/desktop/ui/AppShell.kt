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

/**
 * Two-pane app shell: left = device list sidebar (+ Settings nav entry), right = content slot.
 * Right pane defaults to "No device selected"; a Settings entry swaps it to [SettingsScreen]
 * when `settingsVm` and `configDir` are supplied; selecting a device swaps it to
 * [AppManagerScreen] when `appManagerVm` is supplied, [DeviceInfoScreen] when `deviceInfoVm`
 * is supplied, or [ScreenshotScreen] when `screenshotVm` is supplied (nav buttons toggle pages).
 */
@Composable
fun AppShell(
    vm: DeviceListViewModel,
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel? = null,
    configDir: Path? = null,
    appManagerVm: AppManagerViewModel? = null,
    deviceInfoVm: DeviceInfoViewModel? = null,
    screenshotVm: ScreenshotViewModel? = null,
    logcatVm: LogcatViewModel? = null,
    selectedSerial: MutableStateFlow<String?>? = null,
    onOpenShell: (String) -> Unit = {},
    rightContent: @Composable () -> Unit = { DefaultRightPane() },
) {
    var showSettings by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(NavPage.DEVICE_INFO) }
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
                if (deviceInfoVm != null) {
                    Divider()
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.DEVICE_INFO },
                    ) { Text(if (page == NavPage.DEVICE_INFO && !showSettings) "${Strings.t("nav_device_info")} *" else Strings.t("nav_device_info")) }
                }
                if (screenshotVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.SCREENSHOT },
                    ) { Text(if (page == NavPage.SCREENSHOT && !showSettings) "${Strings.t("nav_screenshot")} *" else Strings.t("nav_screenshot")) }
                }
                if (appManagerVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.APP_MANAGER },
                    ) { Text(if (page == NavPage.APP_MANAGER && !showSettings) "${Strings.t("nav_app_manager")} *" else Strings.t("nav_app_manager")) }
                }
                if (logcatVm != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = false; page = NavPage.LOGCAT },
                    ) { Text(if (page == NavPage.LOGCAT && !showSettings) "${Strings.t("nav_logcat")} *" else Strings.t("nav_logcat")) }
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    onClick = { showSettings = false; page = NavPage.SHELL },
                ) { Text(if (page == NavPage.SHELL && !showSettings) "${Strings.t("nav_shell")} *" else Strings.t("nav_shell")) }
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
                        SettingsScreen(vm = settingsVm, configDir = configDir)
                    }
                    selected != null && page == NavPage.APP_MANAGER && appManagerVm != null -> {
                        AppManagerScreen(vm = appManagerVm)
                    }
                    selected != null && page == NavPage.DEVICE_INFO && deviceInfoVm != null -> {
                        DeviceInfoScreen(vm = deviceInfoVm)
                    }
                    selected != null && page == NavPage.SCREENSHOT && screenshotVm != null -> {
                        ScreenshotScreen(vm = screenshotVm)
                    }
                    selected != null && page == NavPage.LOGCAT && logcatVm != null -> {
                        LogcatScreen(vm = logcatVm)
                    }
                    selected != null && page == NavPage.SHELL -> {
                        ShellScreen(selectedSerial = selected, onOpenShell = onOpenShell)
                    }
                    else -> rightContent()
                }
            }
        }
    }
}

private enum class NavPage { DEVICE_INFO, SCREENSHOT, APP_MANAGER, LOGCAT, SHELL }

@Composable
private fun DefaultRightPane() {
    Text(Strings.t("no_device_selected"))
}
