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
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.adbgui.desktop.ui.i18n.Strings

@Composable
fun ConnectDialog(
    vm: DeviceListViewModel,
    onDismiss: () -> Unit,
) {
    var ip by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("5555") }
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    val ipFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val submitConnect = {
        if (!busy) {
            val p = port.toIntOrNull() ?: 5555
            vm.connect(ip, p)
        }
    }
    LaunchedEffect(Unit) { ipFocus.requestFocus() }

    // Dismiss on successful connect. The VM emits dismissConnect from its background scope;
    // collect it here (LaunchedEffect runs on the Compose UI thread) so the dialog-close
    // state mutation happens on the UI thread — reliable recomposition. The connect button
    // no longer drives dismissal via a captured callback.
    LaunchedEffect(Unit) { vm.dismissConnect.collect { onDismiss() } }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(Strings.t("connect_title")) },
        text = {
            Column {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text(Strings.t("ip_address")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(ipFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }),
                )
                Spacer(Modifier.padding(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(Strings.t("port")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(portFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitConnect() }),
                )
                if (error != null) {
                    Spacer(Modifier.padding(top = 8.dp))
                    InlineMessageBanner(error ?: "", MessageKind.Error)
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
                TextButton(onClick = onDismiss, enabled = !busy) { Text(Strings.t("cancel")) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = submitConnect,
                    enabled = !busy && ip.isNotBlank() && port.isNotBlank(),
                ) { Text(Strings.t("connect")) }
            }
        },
    )
}
