package com.adbgui.desktop.main

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.adbgui.desktop.ui.AppShell
import com.adbgui.desktop.ui.AppManagerViewModel
import com.adbgui.desktop.ui.DeviceListViewModel
import com.adbgui.desktop.ui.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow

fun main() = application {
    val root = CompositionRoot()
    root.start()
    val vm = DeviceListViewModel(root.repository, root.scope)
    val settingsVm = SettingsViewModel(root.settings, root.scope)
    val selectedSerial = remember { MutableStateFlow<String?>(null) }
    val appManagerVm = AppManagerViewModel(root.repository, selectedSerial, root.scope)
    Window(onCloseRequest = ::exitApplication, title = "ADB GUI") {
        MaterialTheme {
            AppShell(
                vm = vm,
                settingsVm = settingsVm,
                configDir = root.configDir,
                appManagerVm = appManagerVm,
                selectedSerial = selectedSerial,
            )
        }
    }
}
