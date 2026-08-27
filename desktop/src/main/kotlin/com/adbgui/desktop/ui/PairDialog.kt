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
fun PairDialog(vm: DeviceListViewModel, onDismiss: () -> Unit) {
    // Phase 1: pairing (IP + pairing port + 6-digit code)
    var pairIp by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // Phase 2: connect after pair succeeds (pairing port is single-use; must use the
    // connect port shown on the device's main Wireless debugging screen)
    var paired by remember { mutableStateOf(false) }
    var connectIp by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }

    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    val pairIpFocus = remember { FocusRequester() }
    val pairPortFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    val connectIpFocus = remember { FocusRequester() }
    val connectPortFocus = remember { FocusRequester() }
    val submitPair = {
        if (!busy && pairIp.isNotBlank() && pairPort.isNotBlank() && code.isNotBlank()) {
            connectIp = pairIp
            vm.pair(pairIp, pairPort.toIntOrNull() ?: 0, code) { r ->
                if (r.success) { paired = true; vm.clearError() }
            }
        }
    }
    val submitConnectPhase2 = {
        if (!busy && connectIp.isNotBlank() && connectPort.isNotBlank()) {
            vm.connect(connectIp, connectPort.toIntOrNull() ?: 0) { r -> if (r.success) onDismiss() }
        }
    }
    LaunchedEffect(paired) {
        if (!paired) pairIpFocus.requestFocus() else connectIpFocus.requestFocus()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(Strings.t("pair_title")) },
        text = {
            Column {
                if (!paired) {
                    // --- Phase 1: pair ---
                    Text(Strings.t("pair_hint"), style = MaterialTheme.typography.caption)
                    Spacer(Modifier.padding(8.dp))
                    OutlinedTextField(
                        value = pairIp,
                        onValueChange = { pairIp = it; if (paired) connectIp = it },
                        label = { Text(Strings.t("ip_address")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(pairIpFocus),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { pairPortFocus.requestFocus() }),
                    )
                    Spacer(Modifier.padding(8.dp))
                    OutlinedTextField(
                        value = pairPort,
                        onValueChange = { pairPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(Strings.t("pair_port")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(pairPortFocus),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { codeFocus.requestFocus() }),
                    )
                    Spacer(Modifier.padding(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(Strings.t("pairing_code")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitPair() }),
                    )
                    if (busy) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Text(Strings.t("pairing"), style = MaterialTheme.typography.caption)
                    }
                } else {
                    // --- Phase 2: connect ---
                    Text(Strings.t("pair_success"), style = MaterialTheme.typography.caption)
                    Spacer(Modifier.padding(8.dp))
                    OutlinedTextField(
                        value = connectIp,
                        onValueChange = { connectIp = it },
                        label = { Text(Strings.t("ip_address")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(connectIpFocus),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { connectPortFocus.requestFocus() }),
                    )
                    Spacer(Modifier.padding(8.dp))
                    OutlinedTextField(
                        value = connectPort,
                        onValueChange = { connectPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(Strings.t("connect_port")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(connectPortFocus),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitConnectPhase2() }),
                    )
                    if (busy) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Text(Strings.t("connecting"), style = MaterialTheme.typography.caption)
                    }
                }
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
                if (!paired) {
                    Button(
                        enabled = !busy && pairIp.isNotBlank() && pairPort.isNotBlank() && code.isNotBlank(),
                        onClick = submitPair,
                    ) { Text(Strings.t("pair")) }
                } else {
                    Button(
                        enabled = !busy && connectIp.isNotBlank() && connectPort.isNotBlank(),
                        onClick = submitConnectPhase2,
                    ) { Text(Strings.t("connect")) }
                }
            }
        },
    )
}
