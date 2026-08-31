package com.adbgui.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Auto-refresh interval for the forwards list (ms). Host `adb forward --list` is cheap & local. */
private const val AUTO_REFRESH_INTERVAL_MS = 5000L

@Composable
fun PortForwardingScreen(
    vm: PortForwardingViewModel,
    selectedSerial: String?,
) {
    val forwards by vm.forwards.collectAsState()
    val localType by vm.localType.collectAsState()
    val localValue by vm.localValue.collectAsState()
    val remoteType by vm.remoteType.collectAsState()
    val remoteValue by vm.remoteValue.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val autoRefresh by vm.autoRefresh.collectAsState()

    // Auto-refresh: poll `adb forward --list` every 5s while a device is selected AND auto is on.
    // Tied to the composition (page visible) — leaves the page → LaunchedEffect cancels → no poll.
    // Keyed on (selectedSerial, autoRefresh) so toggling/switching restarts the loop cleanly.
    LaunchedEffect(selectedSerial, autoRefresh) {
        if (selectedSerial != null && autoRefresh) {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                vm.refresh()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Collapsible help (with a concrete WebView-debug example) ----
        HelpSection(selectedSerial)

        // ---- Add form ----
        Text(Strings.t("pf_add_title"), style = MaterialTheme.typography.h6)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EndpointEditor(
                label = Strings.t("pf_local"),
                type = localType, onTypeChange = vm::setLocalType,
                value = localValue, onValueChange = vm::setLocalValue,
                placeholder = Strings.t("pf_value_placeholder"),
            )
            Text("→")
            EndpointEditor(
                label = Strings.t("pf_remote"),
                type = remoteType, onTypeChange = vm::setRemoteType,
                value = remoteValue, onValueChange = vm::setRemoteValue,
                placeholder = Strings.t("pf_value_placeholder"),
            )
            Button(onClick = { vm.add() }, enabled = !busy) { Text(Strings.t("pf_add")) }
        }

        // ---- Inline error (no modal) — canonical InlineMessageBanner pattern ----
        error?.let { msg ->
            InlineMessageBanner(
                text = msg,
                kind = MessageKind.Error,
                onDismiss = { vm.clearError() },
            )
        }

        // ---- List toolbar ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${forwards.size}", style = MaterialTheme.typography.subtitle2)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.refresh() }, enabled = !busy) { Text(Strings.t("pf_refresh")) }
            TextButton(onClick = { vm.setAutoRefresh(!autoRefresh) }) {
                Text(Strings.t(if (autoRefresh) "pf_auto_on" else "pf_auto_off"))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.removeAll() }, enabled = !busy && forwards.isNotEmpty()) {
                Text(Strings.t("pf_remove_all"))
            }
        }
        Divider(color = AppColors.current.divider)

        // ---- List ----
        if (forwards.isEmpty()) {
            Text(
                Strings.t("pf_empty"),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(forwards, key = { it.local.adbForm() + ">" + it.remote.adbForm() }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${entry.local.adbForm()}  →  ${entry.remote.adbForm()}",
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.remove(entry.local) }, enabled = !busy) {
                            Text(Strings.t("pf_remove"))
                        }
                    }
                    Divider(color = AppColors.current.divider)
                }
            }
        }
    }
}

@Composable
private fun EndpointEditor(
    label: String,
    type: ForwardEndpointType,
    onTypeChange: (ForwardEndpointType) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { menuOpen = true }) { Text(type.prefix) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ForwardEndpointType.entries.forEach { t ->
                    DropdownMenuItem(onClick = { onTypeChange(t); menuOpen = false }) { Text(t.prefix) }
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                placeholder = { Text(placeholder) },
                modifier = Modifier.width(180.dp),
            )
        }
    }
}

/**
 * Collapsible usage help. Default collapsed so the page stays compact; expand for a concrete
 * WebView-debug example + the generic adb-forward explanation. The dynamic line fills the
 * selected serial into the "find webview socket" command so it's copy-paste ready.
 */
@Composable
private fun HelpSection(selectedSerial: String?) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Strings.t("pf_help_toggle"),
                style = MaterialTheme.typography.subtitle2,
                color = MaterialTheme.colors.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(if (open) "▾" else "▸", color = MaterialTheme.colors.primary)
        }
        if (open) {
            Surface(
                color = AppColors.current.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Whole body in one SelectionContainer (via SelectableText) so the example +
                    // commands copy as one block. Monospace so the tcp:9222-style examples align.
                    SelectableText(
                        text = Strings.t("pf_help_body"),
                        style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (!selectedSerial.isNullOrBlank()) {
                        // Dynamic, this-device-specific command — copy-paste ready.
                        SelectableText(
                            text = Strings.t("pf_help_this_device") + "\n" +
                                "adb -s $selectedSerial shell cat /proc/net/unix | grep webview_devtools_remote",
                            style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colors.primary,
                        )
                    }
                }
            }
        }
    }
}
