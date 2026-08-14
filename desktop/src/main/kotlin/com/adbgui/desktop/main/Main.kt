package com.adbgui.desktop.main

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.material.Text
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.unit.dp

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ADB GUI") {
        MaterialTheme {
            Text("ADB GUI")
        }
    }
}
