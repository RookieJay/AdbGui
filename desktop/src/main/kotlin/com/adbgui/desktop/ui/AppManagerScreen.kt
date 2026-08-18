package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.PackageInfo
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.FileDialog
import java.awt.Frame

/**
 * App Manager screen: lists third-party packages for the selected device and offers
 * install / uninstall / clear-data actions. Shows a collapsible inline error box with
 * the raw adb text when a command fails.
 */
@Composable
fun AppManagerScreen(
    vm: AppManagerViewModel,
    modifier: Modifier = Modifier,
) {
    val packages by vm.packages.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    var errorExpanded by remember { mutableStateOf(true) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Toolbar ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Strings.t("app_manager"), style = MaterialTheme.typography.h6)
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = !busy,
                    onClick = { vm.load() },
                ) { Text(Strings.t("refresh")) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        val dialog = FileDialog(Frame(), Strings.t("select_apk"), FileDialog.LOAD)
                        dialog.isMultipleMode = false
                        dialog.setFile("*.apk")
                        dialog.isVisible = true
                        val sel = dialog.file
                        if (sel != null) {
                            val chosen = java.io.File(dialog.directory, sel).absolutePath
                            vm.install(chosen)
                        }
                    },
                ) { Text(Strings.t("select_apk")) }
                Spacer(Modifier.width(8.dp))
                if (busy) CircularProgressIndicator(modifier = Modifier.heightIn(max = 18.dp))
            }

            // --- Inline error box (collapsible) ---
            error?.let { msg ->
                Surface(
                    color = Color(0xFFFFCDD2),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Strings.t("adb_error"), style = MaterialTheme.typography.subtitle2)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { errorExpanded = !errorExpanded }) {
                                Text(if (errorExpanded) Strings.t("collapse") else Strings.t("expand"))
                            }
                        }
                        if (errorExpanded) {
                            SelectableText(msg, style = MaterialTheme.typography.caption)
                        }
                    }
                }
            }

            Divider()

            // --- Package list ---
            if (packages.isEmpty() && !busy) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(Strings.t("no_packages"), style = MaterialTheme.typography.body2)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(packages, key = { it.name }) { pkg ->
                        PackageRow(
                            pkg = pkg,
                            busy = busy,
                            onUninstall = { vm.uninstall(pkg.name) },
                            onClear = { vm.clearData(pkg.name) },
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageRow(
    pkg: PackageInfo,
    busy: Boolean,
    onUninstall: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pkg.name, style = MaterialTheme.typography.body1)
            if (pkg.isSystem) {
                Text(Strings.t("system"), style = MaterialTheme.typography.caption)
            }
        }
        OutlinedButton(enabled = !busy, onClick = onClear) { Text(Strings.t("clear")) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = !busy, onClick = onUninstall) { Text(Strings.t("uninstall")) }
    }
}
