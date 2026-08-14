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
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path

/**
 * Two-pane app shell: left = device list sidebar (+ Settings nav entry), right = content slot.
 * Right pane defaults to "No device selected"; a Settings entry swaps it to [SettingsScreen]
 * when `settingsVm` and `configDir` are supplied; selecting a device swaps it to
 * [AppManagerScreen] when `appManagerVm` is supplied.
 */
@Composable
fun AppShell(
    vm: DeviceListViewModel,
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel? = null,
    configDir: Path? = null,
    appManagerVm: AppManagerViewModel? = null,
    selectedSerial: MutableStateFlow<String?>? = null,
    rightContent: @Composable () -> Unit = { DefaultRightPane() },
) {
    var showSettings by remember { mutableStateOf(false) }
    val serialFlow = selectedSerial ?: remember { MutableStateFlow<String?>(null) }
    val selected by serialFlow.collectAsState()

    MaterialTheme {
        Row(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(280.dp).fillMaxHeight()) {
                DeviceListPane(
                    vm = vm,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSelect = { device -> selectedSerial?.value = device.serial },
                )
                if (settingsVm != null && configDir != null) {
                    Divider()
                    TextButton(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        onClick = { showSettings = !showSettings },
                    ) {
                        Text(if (showSettings) "Back to devices" else "Settings")
                    }
                }
            }
            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
            Surface(modifier = Modifier.fillMaxSize()) {
                when {
                    showSettings && settingsVm != null && configDir != null -> {
                        SettingsScreen(vm = settingsVm, configDir = configDir)
                    }
                    selected != null && appManagerVm != null -> {
                        AppManagerScreen(vm = appManagerVm)
                    }
                    else -> rightContent()
                }
            }
        }
    }
}

@Composable
private fun DefaultRightPane() {
    Text("No device selected")
}
