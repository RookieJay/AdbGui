package com.adbgui.desktop.main

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val root = CompositionRoot()
    root.start()
    Window(onCloseRequest = ::exitApplication, title = "ADB GUI") {
        MaterialTheme { Text("ADB GUI") }
    }
}
