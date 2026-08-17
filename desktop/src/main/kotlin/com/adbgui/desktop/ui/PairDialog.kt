package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings

@Composable
fun PairDialog(vm: DeviceListViewModel, onDismiss: () -> Unit) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("4321") }
    var code by remember { mutableStateOf("") }
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t("pair_title")) },
        text = {
            Column {
                Text(Strings.t("pair_hint"), style = MaterialTheme.typography.caption)
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = ip, singleLine = true,
                    onValueChange = { ip = it },
                    label = { Text(Strings.t("ip_address")) },
                )
                Row {
                    TextField(
                        value = port, singleLine = true,
                        onValueChange = { port = it },
                        label = { Text(Strings.t("port")) },
                        modifier = Modifier.width(120.dp),
                    )
                }
                TextField(
                    value = code, singleLine = true,
                    onValueChange = { code = it },
                    label = { Text(Strings.t("pairing_code")) },
                )
                if (busy) Text(Strings.t("pairing"), style = MaterialTheme.typography.caption)
                error?.let { Text(it, color = Color(0xFFC62828), style = MaterialTheme.typography.caption) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && ip.isNotBlank() && port.isNotBlank() && code.isNotBlank(),
                onClick = {
                    vm.pair(ip, port.toIntOrNull() ?: 0, code) { r ->
                        if (r.success) onDismiss()
                    }
                },
            ) { Text(Strings.t("pair")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.t("cancel")) } },
    )
}
