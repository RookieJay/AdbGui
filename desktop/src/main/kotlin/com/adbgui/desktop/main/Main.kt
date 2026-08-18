package com.adbgui.desktop.main

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.adbgui.desktop.ui.AppShell
import com.adbgui.desktop.ui.AppManagerViewModel
import com.adbgui.desktop.ui.DeviceInfoViewModel
import com.adbgui.desktop.ui.DeviceListViewModel
import com.adbgui.desktop.ui.FileExplorerViewModel
import com.adbgui.desktop.ui.LogcatViewModel
import com.adbgui.desktop.ui.SettingsViewModel
import com.adbgui.desktop.ui.ScreenshotViewModel
import com.adbgui.desktop.ui.SystemOpsViewModel
import com.adbgui.desktop.ui.i18n.Locale
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.core.domain.DeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

fun main() = application {
    val root = CompositionRoot()
    // Set the initial locale on the UI thread BEFORE root.start() touches anything, so the
    // Strings Compose state is created here (avoids cross-thread snapshot read errors).
    val settings = runBlocking { root.settings.load() }
    Strings.set(Locale.fromCode(settings.locale))
    root.start()
    val vm = DeviceListViewModel(root.repository, root.scope)
    val settingsVm = SettingsViewModel(root.settings, root.scope)
    val selectedSerial = remember { MutableStateFlow<String?>(null) }
    val appManagerVm = AppManagerViewModel(root.repository, selectedSerial, root.scope)
    val deviceInfoVm = DeviceInfoViewModel(root.repository, selectedSerial, root.scope)
    val screenshotVm = ScreenshotViewModel(root.repository, selectedSerial, root.scope)
    val logcatController = com.adbgui.core.device.LogcatController(root.commands, root.logger, root.scope)
    val logcatVm = LogcatViewModel(logcatController, selectedSerial, root.scope)
    val systemOpsVm = SystemOpsViewModel(root.repository, selectedSerial, root.scope)
    val fileExplorerVm = FileExplorerViewModel(root.repository, selectedSerial, root.scope)
    val shellLauncher = com.adbgui.desktop.platform.WindowsShellLauncher()
    // Auto-select the first ONLINE device when nothing is validly selected.
    // Never steals an active selection: if the current serial is still online, leave it.
    // (A device going offline counts as an invalid selection → cleared/re-picked, so stale
    //  data for an offline device doesn't linger in the feature pages.)
    LaunchedEffect(Unit) {
        root.repository.devices.collect { list ->
            val current = selectedSerial.value
            val stillValid = current != null && list.any { it.serial == current && it.status == DeviceStatus.ONLINE }
            if (!stillValid) {
                selectedSerial.value = list.firstOrNull { it.status == DeviceStatus.ONLINE }?.serial
            }
        }
    }
    Window(onCloseRequest = ::exitApplication, title = Strings.t("app_title")) {
        MaterialTheme {
            androidx.compose.foundation.text.selection.SelectionContainer {
                AppShell(
                    vm = vm,
                    settingsVm = settingsVm,
                    configDir = root.configDir,
                    appManagerVm = appManagerVm,
                    deviceInfoVm = deviceInfoVm,
                    screenshotVm = screenshotVm,
                    logcatVm = logcatVm,
                    systemOpsVm = systemOpsVm,
                    fileExplorerVm = fileExplorerVm,
                    selectedSerial = selectedSerial,
                    onOpenShell = { serial ->
                        kotlinx.coroutines.runBlocking {
                            val adb = root.locator.locate()
                            shellLauncher.open(adb.path, serial)
                        }
                    },
                )
            }
        }
    }
}
