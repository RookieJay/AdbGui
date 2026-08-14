package com.adbgui.desktop.main

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.adbgui.desktop.ui.AppShell
import com.adbgui.desktop.ui.DeviceListViewModel

fun main() = application {
    val root = CompositionRoot()
    root.start()
    val vm = DeviceListViewModel(root.repository, root.scope)
    Window(onCloseRequest = ::exitApplication, title = "ADB GUI") {
        MaterialTheme {
            AppShell(vm = vm)
        }
    }
}
