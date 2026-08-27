package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SystemInfoScreen(
    vm: SystemInfoViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
) {
    val result by vm.result.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val currentCommand by vm.currentCommand.collectAsState()
    val packages by vm.packages.collectAsState()
    val selectedPackage by vm.selectedPackage.collectAsState()
    val packagesBusy by vm.packagesBusy.collectAsState()
    val packagesError by vm.packagesError.collectAsState()

    val groups = remember { systemInfoCommands.groupBy { it.group } }
    var pkgMenuOpen by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Row(modifier.fillMaxSize()) {
        // ---- Left: package selector + grouped command list ----
        Column(Modifier.width(300.dp).fillMaxHeight().padding(8.dp)) {
            // Package dropdown (lazy load on first open)
            Box {
                OutlinedButton(
                    onClick = {
                        if (vm.packages.value.isEmpty() && !packagesBusy) vm.loadPackages()
                        pkgMenuOpen = true
                    },
                    enabled = !packagesBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(selectedPackage ?: Strings.t("si_select_package"))
                }
                DropdownMenu(expanded = pkgMenuOpen, onDismissRequest = { pkgMenuOpen = false }) {
                    if (packagesBusy) {
                        DropdownMenuItem(onClick = {}) { Text(Strings.t("si_packages_loading")) }
                    } else {
                        packages.forEach { p ->
                            DropdownMenuItem(
                                onClick = { vm.selectPackage(p.name); pkgMenuOpen = false }
                            ) { Text(p.name) }
                        }
                    }
                }
            }
            packagesError?.let { InlineMessageBanner(it, MessageKind.Error) }
            Divider(Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (groupKey, cmds) ->
                    item(key = groupKey) {
                        Text(
                            Strings.t(groupKey),
                            style = MaterialTheme.typography.subtitle1,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(cmds, key = { it.titleKey }) { c ->
                        val needsPkgAndMissing = c.needsPackage && selectedPackage == null
                        CommandRow(
                            title = Strings.t(c.titleKey),
                            enabled = !busy && !needsPkgAndMissing,
                            onClick = { vm.runCommand(c) },
                        )
                    }
                }
            }
        }

        Divider(Modifier.fillMaxHeight().width(1.dp))

        // ---- Right: output ----
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // Serial label (AppShell only renders this page when a device is selected)
            Text(
                "adb -s $selectedSerial",
                style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            // Header: title + copy + save
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    currentCommand?.let { Strings.t(it.titleKey) } ?: Strings.t("si_empty"),
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    enabled = result != null,
                    onClick = {
                        val r = result ?: return@OutlinedButton
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(r), null)
                        }.onSuccess { savedFile = null; saveError = Strings.t("copied") }
                            .onFailure { saveError = Strings.t("copy_failed") }
                    },
                ) { Text(Strings.t("copy")) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = result != null,
                    onClick = {
                        val r = result ?: return@OutlinedButton
                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        val target = com.adbgui.desktop.platform.FileDialogs.saveFile(
                            title = Strings.t("si_export_title"),
                            defaultName = "sysinfo_$stamp.txt",
                        )
                        if (target != null) {
                            val f = File(target)
                            runCatching { f.writeText(r) }
                                .onSuccess { savedFile = f; saveError = null }
                                .onFailure { saveError = Strings.t("status_save_failed").format(it.message) }
                        }
                    },
                ) { Text(Strings.t("save")) }
            }
            // Status line (copied / saved path / save error)
            savedFile?.let { Text(it.absolutePath, style = MaterialTheme.typography.caption) }
            saveError?.let { SelectableText(it, style = MaterialTheme.typography.caption) }

            // Body
            Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
                when {
                    busy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> SelectableText(
                        error!!,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.error,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                    result != null -> SelectableText(
                        result!!,
                        style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                    else -> Text(Strings.t("si_no_command"), Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun CommandRow(title: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.fillMaxWidth())
    }
}
