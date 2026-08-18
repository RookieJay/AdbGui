package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings

/**
 * Shell page — opens an external OS terminal running `adb -s <serial> shell`.
 * Stateless: receives the selected serial + the [onOpenShell] callback (which resolves the
 * adb path + spawns a detached terminal). Failures (adb not found / spawn IOException) are
 * surfaced inline per spec §5 — no modal interruption.
 */
@Composable
fun ShellScreen(
    selectedSerial: String?,
    onOpenShell: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var error by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        if (selectedSerial == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Strings.t("no_device_selected"), style = MaterialTheme.typography.body2)
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(Strings.t("shell"), style = MaterialTheme.typography.h6)
                Text(selectedSerial, style = MaterialTheme.typography.body2)
                Button(onClick = {
                    error = null
                    runCatching { onOpenShell(selectedSerial) }.onFailure { error = it.message }
                }) { Text(Strings.t("open_shell")) }
                error?.let { msg ->
                    Surface(color = Color(0xFFFFCDD2), modifier = Modifier.fillMaxWidth()) {
                        SelectableText(msg, style = MaterialTheme.typography.caption, modifier = Modifier.padding(6.dp))
                    }
                }
            }
        }
    }
}
