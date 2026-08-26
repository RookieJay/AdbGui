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
import androidx.compose.material.AlertDialog
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.DeviceView
import com.adbgui.desktop.ui.i18n.Strings

@Composable
fun DeviceListPane(
    vm: DeviceListViewModel,
    modifier: Modifier = Modifier,
    selected: String? = null,
    onSelect: (DeviceView) -> Unit = {},
    onReconnect: (String, Int) -> Unit = { _, _ -> },
) {
    var showConnect by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }
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
                Text(Strings.t("devices"), style = MaterialTheme.typography.subtitle1)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showPair = true }) { Text(Strings.t("pair")) }
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

            // Inline error — dismissible so a stale-port hint (which can be long) doesn't
            // permanently block the list. Cleared via vm.clearError(); also auto-cleared on
            // the next connect/reconnect attempt. Layout: weight(1f) must sit on a DIRECT
            // child of Row (a Box) — SelectableText wraps its Text in a SelectionContainer,
            // so a modifier passed to SelectableText lands on the inner Text and the long
            // error string would otherwise push the dismiss button off-screen.
            if (error != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFB00020)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        SelectableText(
                            error ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.caption,
                        )
                    }
                    TextButton(
                        onClick = { vm.clearError() },
                        modifier = Modifier.padding(start = 8.dp).width(40.dp),
                    ) {
                        Text("×", color = Color.White, style = MaterialTheme.typography.h6)
                    }
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

    if (showPair) {
        PairDialog(
            vm = vm,
            onDismiss = { showPair = false; vm.clearError() },
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
    var showForgetConfirm by remember { mutableStateOf(false) }
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
                    val focusRequester = remember { FocusRequester() }
                    androidx.compose.runtime.LaunchedEffect(renaming) {
                        if (renaming) focusRequester.requestFocus()
                    }
                    TextField(
                        value = aliasDraft,
                        onValueChange = { aliasDraft = it },
                        modifier = Modifier.widthIn(max = 180.dp)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { e ->
                                if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                                    onRename(aliasDraft.ifBlank { null })
                                    renaming = false
                                    true
                                } else false
                            },
                        singleLine = true,
                    )
                    Row {
                        TextButton(onClick = {
                            onRename(aliasDraft.ifBlank { null })
                            renaming = false
                        }) { Text(Strings.t("ok")) }
                        TextButton(onClick = { renaming = false }) { Text(Strings.t("cancel")) }
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
                        }) { Text(Strings.t("reconnect")) }
                    }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        aliasDraft = device.alias ?: ""
                        renaming = true
                    }) { Text(Strings.t("rename")) }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        onDisconnect()
                    }) { Text(Strings.t("disconnect")) }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        showForgetConfirm = true
                    }) { Text(Strings.t("forget")) }
                }
            }
        }
        Divider()
    }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text(Strings.t("forget_confirm_title")) },
            text = { Text(Strings.t("forget_confirm_body").format(device.alias ?: device.serial)) },
            confirmButton = { TextButton(onClick = { showForgetConfirm = false; onForget() }) { Text(Strings.t("forget")) } },
            dismissButton = { TextButton(onClick = { showForgetConfirm = false }) { Text(Strings.t("cancel")) } },
        )
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
