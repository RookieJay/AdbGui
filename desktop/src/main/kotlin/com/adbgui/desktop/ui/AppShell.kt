package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Two-pane app shell: left = device list sidebar, right = content slot
 * (filled by later tasks; currently a placeholder pane).
 */
@Composable
fun AppShell(
    vm: DeviceListViewModel,
    modifier: Modifier = Modifier,
    rightContent: @Composable () -> Unit = { DefaultRightPane() },
) {
    MaterialTheme {
        Row(modifier = modifier.fillMaxSize()) {
            DeviceListPane(
                vm = vm,
                modifier = Modifier.width(280.dp).fillMaxHeight(),
            )
            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
            Surface(modifier = Modifier.fillMaxSize()) {
                rightContent()
            }
        }
    }
}

@Composable
private fun DefaultRightPane() {
    androidx.compose.material.Text("No device selected")
}
