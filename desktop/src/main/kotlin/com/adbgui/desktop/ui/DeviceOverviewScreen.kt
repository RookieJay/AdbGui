package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.ScrcpyMode
import com.adbgui.core.domain.ScrcpyOptions
import com.adbgui.desktop.platform.ScrcpyInstaller
import com.adbgui.desktop.platform.ScrcpyLauncher
import com.adbgui.desktop.platform.WindowsScrcpyLocator
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.launch

@Composable
fun DeviceOverviewScreen(
    deviceInfoVm: DeviceInfoViewModel,
    screenshotVm: ScreenshotViewModel,
    remoteVm: RemoteViewModel,
    selectedSerial: String?,
    scrcpyInstaller: ScrcpyInstaller,
    scrcpyLocator: WindowsScrcpyLocator,
    scrcpyLauncher: ScrcpyLauncher,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Strings.t("device_overview"), style = MaterialTheme.typography.h5)
            // --- Top row: device info (left) + screenshot (right) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DeviceInfoScreen(vm = deviceInfoVm, modifier = Modifier.weight(1f))
                ScreenshotScreen(vm = screenshotVm, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            // --- Remote (full width below) ---
            RemoteScreen(vm = remoteVm, selectedSerial = selectedSerial, modifier = Modifier.fillMaxWidth())
            Divider()
            // --- scrcpy section ---
            Text(Strings.t("scrcpy"), style = MaterialTheme.typography.subtitle1)
            val scrcpyPath = remember { mutableStateOf<String?>(null) }
            val scrcpyStatus = remember { mutableStateOf("installing") } // installing | installed | failed
            val scrcpyRunning = remember { mutableStateOf(false) }
            val scrcpyError = remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                try {
                    scrcpyPath.value = scrcpyInstaller.ensureInstalled()
                    scrcpyStatus.value = "installed"
                } catch (e: Exception) {
                    scrcpyStatus.value = "failed"
                    scrcpyError.value = e.message
                }
            }

            when (scrcpyStatus.value) {
                "installed" -> {
                    Text(Strings.t("scrcpy_status_installed"))
                    // Mode toggle — EXTERNAL default; full settings later.
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = true, onClick = {})
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_mode_external"))
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = false, onClick = {})
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.t("scrcpy_mode_embedded"))
                    }
                    Row {
                        Button(
                            enabled = selectedSerial != null && !scrcpyRunning.value,
                            onClick = {
                                val path = scrcpyPath.value ?: return@Button
                                val serial = selectedSerial ?: return@Button
                                scrcpyRunning.value = true
                                scrcpyError.value = null
                                scope.launch {
                                    try {
                                        scrcpyLauncher.open(path, serial, ScrcpyOptions(), ScrcpyMode.EXTERNAL)
                                    } catch (e: Exception) {
                                        scrcpyError.value = e.message
                                        scrcpyRunning.value = false
                                    }
                                }
                            },
                        ) { Text(Strings.t("start_scrcpy")) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = scrcpyRunning.value,
                            onClick = {
                                scrcpyLauncher.stop()
                                scrcpyRunning.value = false
                            },
                        ) { Text(Strings.t("stop_scrcpy")) }
                    }
                }
                "installing" -> Text(Strings.t("scrcpy_status_installing"))
                "failed" -> {
                    Text(Strings.t("scrcpy_status_failed"))
                    scrcpyError.value?.let { SelectableText(it) }
                    Button(onClick = {
                        scrcpyStatus.value = "installing"
                        scope.launch {
                            try {
                                scrcpyPath.value = scrcpyInstaller.ensureInstalled()
                                scrcpyStatus.value = "installed"
                            } catch (e: Exception) {
                                scrcpyStatus.value = "failed"
                                scrcpyError.value = e.message
                            }
                        }
                    }) { Text(Strings.t("scrcpy_retry")) }
                }
            }
        }
    }
}
