package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        if (selectedSerial == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Strings.t("no_device_selected"), style = MaterialTheme.typography.body2)
            }
            return@Surface
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // D-pad: ↑ / ← OK → / ↓
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.sendKey(19) }, enabled = !busy, modifier = Modifier.size(64.dp)) { Text("↑") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.sendKey(21) }, enabled = !busy, modifier = Modifier.size(64.dp)) { Text("←") }
                    Button(onClick = { vm.sendKey(23) }, enabled = !busy, modifier = Modifier.size(64.dp)) { Text("OK") }
                    Button(onClick = { vm.sendKey(22) }, enabled = !busy, modifier = Modifier.size(64.dp)) { Text("→") }
                }
                Button(onClick = { vm.sendKey(20) }, enabled = !busy, modifier = Modifier.size(64.dp)) { Text("↓") }
            }
            // Navigation buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.sendKey(4) }, enabled = !busy) { Text(Strings.t("back")) }
                OutlinedButton(onClick = { vm.sendKey(3) }, enabled = !busy) { Text(Strings.t("home")) }
                OutlinedButton(onClick = { vm.sendKey(82) }, enabled = !busy) { Text(Strings.t("menu")) }
            }
            // Custom buttons
            Text(Strings.t("custom_buttons"), style = MaterialTheme.typography.subtitle2)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) {
                items(customButtons, key = { it.id }) { btn ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Box(
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) menuOpen = true
                                }
                            }
                        }
                    ) {
                        OutlinedButton(onClick = { vm.sendKey(btn.keycode) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(btn.label)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(onClick = { menuOpen = false; editingButton = btn }) { Text(Strings.t("edit_button")) }
                            DropdownMenuItem(onClick = { menuOpen = false; vm.removeButton(btn.id) }) { Text(Strings.t("remove")) }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { showAdd = true }) { Text(Strings.t("add_button")) }
            error?.let { SelectableText(it, modifier = Modifier.padding(4.dp)) }
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
                TextField(value = label, singleLine = true, onValueChange = { label = it }, label = { Text(Strings.t("button_label")) })
                Spacer(Modifier.size(8.dp))
                TextField(value = keycodeStr, singleLine = true, onValueChange = { keycodeStr = it }, label = { Text(Strings.t("keycode")) })
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
