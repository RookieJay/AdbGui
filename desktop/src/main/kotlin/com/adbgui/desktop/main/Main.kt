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
import com.adbgui.desktop.ui.SystemInfoViewModel
import com.adbgui.desktop.ui.i18n.Locale
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.core.domain.DeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

fun main() = application {
    // Everything below lives in the composable application scope, so it must be remembered
    // or each recomposition recreates it — recreating CompositionRoot would spawn a fresh
    // DeviceTracker (new track-devices stream) + AdbServerController per recomposition,
    // and recreating ViewModels would lose instance-local state (the screenshot bug).
    val root = remember { CompositionRoot() }
    // Load settings once on the UI thread; Strings Compose state must be created here
    // (avoids cross-thread snapshot read errors). Set the locale before Window reads it.
    val settings = remember { runBlocking { root.settings.load() } }
    remember { Strings.set(Locale.fromCode(settings.locale)) }
    // Start the adb tracker exactly once — start() spawns a track-devices stream each call.
    LaunchedEffect(Unit) { root.start() }
    val vm = remember { DeviceListViewModel(root.repository, root.scope) }
    val settingsVm = remember { SettingsViewModel(root.settings, root.scope) }
    val selectedSerial = remember { MutableStateFlow<String?>(null) }
    var showScreenshot by remember { mutableStateOf(false) }
    var screenshotLoading by remember { mutableStateOf(false) }
    val appConsoleVm = remember { AppConsoleViewModel(root.repository, selectedSerial, root.scope) }
    val deviceInfoVm = remember { DeviceInfoViewModel(root.repository, selectedSerial, root.scope) }
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
    val logcatController = remember { com.adbgui.core.device.LogcatController(root.commands, root.logger, root.scope) }
    val logcatVm = remember { LogcatViewModel(logcatController, selectedSerial, root.scope) }
    val systemOpsVm = remember { SystemOpsViewModel(root.repository, selectedSerial, root.scope) }
    val systemInfoVm = remember { SystemInfoViewModel(root.repository, selectedSerial, root.scope) }
    val remoteVm = remember { RemoteViewModel(root.repository, selectedSerial, root.settings, root.scope) }
    val fileExplorerVm = remember { FileExplorerViewModel(root.repository, selectedSerial, root.scope) }
    val shellLauncher = remember { com.adbgui.desktop.platform.WindowsShellLauncher() }
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
                systemInfoVm = systemInfoVm,
                fileExplorerVm = fileExplorerVm,
                selectedSerial = selectedSerial,
                onOpenShell = { serial ->
                    kotlinx.coroutines.runBlocking {
                        val adb = root.locator.locate()
                        shellLauncher.open(adb.path, serial)
                    }
                },
                repo = root.repository,
                adbLocator = root.locator,
            )
        }
    }
    // Independent screenshot window — opened on demand from Device Overview so the
    // preview does not occupy the overview page. One-shot: closing discards state.
    if (showScreenshot) {
        ScreenshotWindow(vm = screenshotVm, onClose = { showScreenshot = false })
    }
}
