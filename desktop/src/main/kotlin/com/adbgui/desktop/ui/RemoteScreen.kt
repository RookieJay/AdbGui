package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.RemoteButton
import com.adbgui.desktop.ui.i18n.Strings

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RemoteScreen(
    vm: RemoteViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
) {
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val customButtons by vm.customButtons.collectAsState()
    var editingButton by remember { mutableStateOf<RemoteButton?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<RemoteButton?>(null) }

    // Content-only (no Surface/padding/title of its own): always hosted in a SectionCard in
    // DeviceOverview, which supplies the card surface + padding + header. Avoids double-padding
    // and a duplicate "遥控" header.
    if (selectedSerial == null) {
        // Unreachable in practice (AppShell only renders this page when a device is selected),
        // but keep a consistent empty state if the contract is ever violated.
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            EmptyState(title = Strings.t("no_device_selected"), hint = Strings.t("no_device_hint"))
        }
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            // D-pad
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { vm.sendKey(19) }, enabled = !busy, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { vm.sendKey(21) }, enabled = !busy, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null) }
                    Button(onClick = { vm.sendKey(23) }, enabled = !busy, modifier = Modifier.size(56.dp)) { Text("OK") }
                    Button(onClick = { vm.sendKey(22) }, enabled = !busy, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null) }
                }
                Button(onClick = { vm.sendKey(20) }, enabled = !busy, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) }
            }
            // Nav buttons
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { vm.sendKey(4) }, enabled = !busy) { Text(Strings.t("back")) }
                OutlinedButton(onClick = { vm.sendKey(3) }, enabled = !busy) { Text(Strings.t("home")) }
                OutlinedButton(onClick = { vm.sendKey(82) }, enabled = !busy) { Text(Strings.t("menu")) }
            }
            // Text input — types into the device's focused field (adb shell input text).
            var textDraft by remember { mutableStateOf("") }
            val sendText = { if (textDraft.isNotBlank()) { vm.sendText(textDraft); textDraft = "" } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    label = { Text(Strings.t("text_input_label")) },
                    placeholder = { Text(Strings.t("text_input_placeholder")) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { sendText() }),
                )
                Button(onClick = { sendText() }, enabled = !busy && textDraft.isNotBlank()) { Text(Strings.t("send_text")) }
            }
            // Custom buttons — simple Column of Rows (no LazyGrid, works inside scroll)
            if (customButtons.isNotEmpty()) {
                Text(Strings.t("custom_buttons"), style = MaterialTheme.typography.caption)
                // 3 per row, simple flow layout
                val rows = customButtons.chunked(3)
                rows.forEach { rowButtons ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowButtons.forEach { btn ->
                            var menuOpen by remember(btn.id) { mutableStateOf(false) }
                            Box(
                                Modifier.pointerInput(btn.id) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) menuOpen = true
                                        }
                                    }
                                }
                            ) {
                                OutlinedButton(onClick = { vm.sendKey(btn.keycode) }, enabled = !busy) { Text(remoteButtonLabel(btn)) }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(onClick = { menuOpen = false; editingButton = btn }) { Text(Strings.t("edit_button")) }
                                    DropdownMenuItem(onClick = { menuOpen = false; confirmDelete = btn }) { Text(Strings.t("remove")) }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { showAdd = true }) { Text("+ ${Strings.t("add_button")}") }
            error?.let { msg ->
                InlineMessageBanner(msg, MessageKind.Error, onDismiss = { vm.clearError() })
            }
        }

    if (showAdd) {
        ButtonEditDialog(
            title = Strings.t("add_button"),
            initialLabel = "", initialKeycode = "",
            onConfirm = { label, keycode -> vm.addButton(label, keycode); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
    editingButton?.let { btn ->
        ButtonEditDialog(
            title = Strings.t("edit_button"),
            initialLabel = btn.label, initialKeycode = btn.keycode.toString(),
            onConfirm = { label, keycode -> vm.updateButton(btn.id, label, keycode); editingButton = null },
            onDismiss = { editingButton = null },
        )
    }
    confirmDelete?.let { btn ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(Strings.t("remove")) },
            text = { Text("${btn.label} (keycode: ${btn.keycode})?") },
            confirmButton = { TextButton(onClick = { vm.removeButton(btn.id); confirmDelete = null }) { Text(Strings.t("remove")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(Strings.t("cancel")) } },
        )
    }
}

@Composable
private fun remoteButtonLabel(btn: RemoteButton): String {
    val key = when (btn.id) {
        "vol_up" -> "btn_vol_up"
        "vol_down" -> "btn_vol_down"
        "vol_mute" -> "btn_vol_mute"
        "power" -> "btn_power"
        "app_switch" -> "btn_app_switch"
        else -> return btn.label  // user-added button → show as-is
    }
    return Strings.t(key)
}

@Composable
private fun ButtonEditDialog(
    title: String, initialLabel: String, initialKeycode: String,
    onConfirm: (String, Int) -> Unit, onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var keycodeStr by remember { mutableStateOf(initialKeycode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = label, singleLine = true, onValueChange = { label = it }, label = { Text(Strings.t("button_label")) })
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(value = keycodeStr, singleLine = true, onValueChange = { keycodeStr = it }, label = { Text(Strings.t("keycode")) })
            }
        },
        confirmButton = {
            val keycode = keycodeStr.toIntOrNull()
            TextButton(enabled = label.isNotBlank() && keycode != null,
                onClick = { keycode?.let { onConfirm(label, it) } }) { Text(Strings.t("ok")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.t("cancel")) } },
    )
}
