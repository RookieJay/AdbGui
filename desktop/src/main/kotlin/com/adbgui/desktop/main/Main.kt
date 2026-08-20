package com.adbgui.desktop.main

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.adbgui.desktop.ui.AppShell
import com.adbgui.desktop.ui.AppConsoleViewModel
import com.adbgui.desktop.ui.DeviceInfoViewModel
import com.adbgui.desktop.ui.DeviceListViewModel
import com.adbgui.desktop.ui.FileExplorerViewModel
import com.adbgui.desktop.ui.LogcatViewModel
import com.adbgui.desktop.ui.ScreenshotWindow
import com.adbgui.desktop.ui.SettingsViewModel
import com.adbgui.desktop.ui.ScreenshotViewModel
import com.adbgui.desktop.ui.RemoteViewModel
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
    var showScreenshot by remember { mutableStateOf(false) }
    var screenshotLoading by remember { mutableStateOf(false) }
    val appConsoleVm = AppConsoleViewModel(root.repository, selectedSerial, root.scope)
    val deviceInfoVm = DeviceInfoViewModel(root.repository, selectedSerial, root.scope)
    val screenshotVm = remember { ScreenshotViewModel(root.repository, selectedSerial, root.scope, root.logger) }
    val captureDone by screenshotVm.captureDone.collectAsState()
    // Open the screenshot window once capture finishes (success or failure).
    // On success it shows the shot; on failure it shows the error text instead of
    // leaving the user staring at a spinner that already stopped.
    LaunchedEffect(captureDone) {
        if (captureDone > 0L && screenshotLoading) {
            screenshotLoading = false
            showScreenshot = true
            root.logger.info("[screenshot] opening window captureDone=$captureDone image=${screenshotVm.image.value?.size ?: "null"}")
        }
    }
    val logcatController = com.adbgui.core.device.LogcatController(root.commands, root.logger, root.scope)
    val logcatVm = LogcatViewModel(logcatController, selectedSerial, root.scope)
    val systemOpsVm = SystemOpsViewModel(root.repository, selectedSerial, root.scope)
    val remoteVm = RemoteViewModel(root.repository, selectedSerial, root.settings, root.scope)
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
            AppShell(
                vm = vm,
                settingsVm = settingsVm,
                configDir = root.configDir,
                deviceOverviewDeviceInfoVm = deviceInfoVm,
                deviceOverviewRemoteVm = remoteVm,
                onOpenScreenshot = {
                    root.logger.info("[screenshot] button clicked")
                    screenshotVm.capture()
                    screenshotLoading = true
                },
                screenshotLoading = screenshotLoading,
                scrcpyInstaller = root.scrcpyInstaller,
                scrcpyLocator = root.scrcpyLocator,
                scrcpyLauncher = root.scrcpyLauncher,
                appConsoleVm = appConsoleVm,
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
    // Independent screenshot window — opened on demand from Device Overview so the
    // preview does not occupy the overview page. One-shot: closing discards state.
    if (showScreenshot) {
        ScreenshotWindow(vm = screenshotVm, onClose = { showScreenshot = false })
    }
}
