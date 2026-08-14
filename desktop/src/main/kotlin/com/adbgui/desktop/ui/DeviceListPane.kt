package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.DeviceView

@Composable
fun DeviceListPane(
    vm: DeviceListViewModel,
    modifier: Modifier = Modifier,
    selected: String? = null,
    onSelect: (DeviceView) -> Unit = {},
    onReconnect: (String, Int) -> Unit = { _, _ -> },
) {
    var showConnect by remember { mutableStateOf(false) }
    val devices by vm.devices.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    Surface(modifier = modifier, color = MaterialTheme.colors.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Devices", style = MaterialTheme.typography.subtitle1)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showConnect = true }) {
                    Text("+", style = MaterialTheme.typography.h6)
                }
            }
            Divider()

            // Device list
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(devices, key = { it.serial }) { device ->
                    DeviceRow(
                        device = device,
                        selected = selected,
                        onRename = { newAlias ->
                            // simple inline rename prompt: for now, set alias directly to serial-as-alias placeholder
                            // (real rename dialog deferred to a later task)
                            vm.setAlias(device.serial, newAlias)
                        },
                        onForget = { vm.forget(device.serial) },
                        onDisconnect = { vm.disconnect(device.serial) },
                        onSelect = { onSelect(device) },
                        onReconnect = onReconnect,
                    )
                }
            }

            // Inline error
            if (error != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFB00020)).padding(8.dp),
                ) {
                    Text(error ?: "", color = Color.White, style = MaterialTheme.typography.caption)
                }
            }
        }
    }

    if (showConnect) {
        ConnectDialog(
            vm = vm,
            onDismiss = { showConnect = false },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeviceRow(
    device: DeviceView,
    selected: String? = null,
    onRename: (String?) -> Unit,
    onForget: () -> Unit,
    onDisconnect: () -> Unit,
    onSelect: () -> Unit = {},
    onReconnect: (String, Int) -> Unit = { _, _ -> },
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var aliasDraft by remember { mutableStateOf(device.alias ?: "") }
    val isSelected = device.serial == selected
    val rowBg = if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.14f) else Color.Transparent

    Column(modifier = Modifier.fillMaxWidth().background(rowBg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable { onSelect() }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press &&
                                event.button == PointerButton.Secondary
                            ) {
                                menuOpen = true
                            }
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(isLive = device.isLive)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (renaming) {
                    TextField(
                        value = aliasDraft,
                        onValueChange = { aliasDraft = it },
                        modifier = Modifier.widthIn(max = 180.dp),
                        singleLine = true,
                    )
                    Row {
                        TextButton(onClick = {
                            onRename(aliasDraft.ifBlank { null })
                            renaming = false
                        }) { Text("OK") }
                        TextButton(onClick = { renaming = false }) { Text("Cancel") }
                    }
                } else {
                    Text(
                        text = device.alias ?: device.serial,
                        style = MaterialTheme.typography.body1,
                    )
                    val sub = buildString {
                        append(device.serial)
                        device.wirelessIp?.let { append(" · $it:${device.wirelessPort ?: 5555}") }
                    }
                    Text(sub, style = MaterialTheme.typography.caption)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Text("⋮", style = MaterialTheme.typography.h6)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (device.wirelessIp != null && device.wirelessPort != null) {
                        DropdownMenuItem(onClick = {
                            menuOpen = false
                            onReconnect(device.wirelessIp!!, device.wirelessPort!!)
                        }) { Text("Reconnect") }
                    }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        aliasDraft = device.alias ?: ""
                        renaming = true
                    }) { Text("Rename") }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        onDisconnect()
                    }) { Text("Disconnect") }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        onForget()
                    }) { Text("Forget") }
                }
            }
        }
        Divider()
    }
}

@Composable
private fun StatusDot(isLive: Boolean) {
    val color = if (isLive) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color),
    )
}
