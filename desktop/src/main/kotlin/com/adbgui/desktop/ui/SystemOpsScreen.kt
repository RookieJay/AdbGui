package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
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
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.RebootMode
import com.adbgui.desktop.ui.i18n.Strings

@Composable
fun SystemOpsScreen(
    vm: SystemOpsViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
) {
    val error by vm.error.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    var pendingReboot by remember { mutableStateOf<RebootMode?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        if (selectedSerial == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Strings.t("no_device_selected"), style = MaterialTheme.typography.body2)
            }
            return@Surface
        }
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Strings.t("system_ops"), style = MaterialTheme.typography.h6)

            // Reboot buttons (with confirm).
            Text(Strings.t("reboot"), style = MaterialTheme.typography.subtitle2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    RebootMode.NORMAL to "reboot_normal",
                    RebootMode.RECOVERY to "reboot_recovery",
                    RebootMode.BOOTLOADER to "reboot_bootloader",
                    RebootMode.SIDELOAD to "reboot_sideload",
                ).forEach { (mode, key) ->
                    OutlinedButton(enabled = !busy, onClick = { pendingReboot = mode }) {
                        Text(Strings.t(key))
                    }
                }
            }

            // Root / remount / shell moved to Device Overview (roadmap G5). System Ops keeps
            // state-affecting operations only (reboot here; monkey压测 reserved per G4).

            message?.let { msg -> InlineMessageBanner(msg.trim(), MessageKind.Success) }
            error?.let { msg -> InlineMessageBanner(msg, MessageKind.Error) }
        }
    }

    // Reboot confirm dialog.
    pendingReboot?.let { mode ->
        AlertDialog(
            onDismissRequest = { pendingReboot = null },
            title = { Text(Strings.t("reboot_confirm_title")) },
            text = { Text(Strings.t("reboot_confirm_body").format(rebootLabel(mode))) },
            confirmButton = {
                TextButton(onClick = { pendingReboot = null; vm.reboot(mode) }) { Text(Strings.t("reboot")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReboot = null }) { Text(Strings.t("cancel")) }
            },
        )
    }
}

private fun rebootLabel(mode: RebootMode): String = when (mode) {
    RebootMode.NORMAL -> Strings.t("reboot_normal")
    RebootMode.RECOVERY -> Strings.t("reboot_recovery")
    RebootMode.BOOTLOADER -> Strings.t("reboot_bootloader")
    RebootMode.SIDELOAD -> Strings.t("reboot_sideload")
}
