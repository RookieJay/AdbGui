package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun ConnectDialog(
    vm: DeviceListViewModel,
    onDismiss: () -> Unit,
) {
    var ip by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("5555") }
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connect to device") },
        text = {
            Column {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Spacer(Modifier.padding(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (error != null) {
                    Spacer(Modifier.padding(top = 8.dp))
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).padding(end = 8.dp))
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val p = port.toIntOrNull() ?: 5555
                        vm.connect(ip.ifBlank { "127.0.0.1" }, p) { r ->
                            if (r.success) onDismiss()
                        }
                    },
                    enabled = !busy,
                ) { Text("Connect") }
            }
        },
    )
}
