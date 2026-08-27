package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path

@Composable
fun AppShell(
    vm: DeviceListViewModel,
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel? = null,
    configDir: Path? = null,
    deviceOverviewDeviceInfoVm: DeviceInfoViewModel? = null,
    deviceOverviewRemoteVm: RemoteViewModel? = null,
    onOpenScreenshot: () -> Unit = {},
    screenshotLoading: Boolean = false,
    scrcpyInstaller: com.adbgui.desktop.platform.ScrcpyInstaller? = null,
    scrcpyLocator: com.adbgui.desktop.platform.WindowsScrcpyLocator? = null,
    scrcpyLauncher: com.adbgui.desktop.platform.ScrcpyLauncher? = null,
    appConsoleVm: AppConsoleViewModel? = null,
    logcatVm: LogcatViewModel? = null,
    systemOpsVm: SystemOpsViewModel? = null,
    systemInfoVm: SystemInfoViewModel? = null,
    fileExplorerVm: FileExplorerViewModel? = null,
    selectedSerial: MutableStateFlow<String?>? = null,
    onOpenShell: (String) -> Unit = {},
    repo: com.adbgui.core.device.DeviceRepository? = null,
    adbLocator: com.adbgui.core.adb.AdbLocator? = null,
) {
    var page by remember { mutableStateOf(NavPage.DEVICE_OVERVIEW) }
    var showConnect by remember { mutableStateOf(false) }
    val serialFlow = selectedSerial ?: remember { MutableStateFlow<String?>(null) }
    val selected by serialFlow.collectAsState()

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(280.dp).fillMaxHeight()
                .background(MaterialTheme.colors.surface),
        ) {
            DeviceListPane(
                vm = vm,
                modifier = Modifier.fillMaxWidth().weight(1f),
                selected = selected,
                onSelect = { device -> selectedSerial?.value = device.serial },
                onReconnect = { ip, port -> vm.reconnect(ip, port) },
                onOpenConnect = { showConnect = true },
            )
            Divider()

            // Feature nav items — only render the ones whose VM is wired.
            val navItems: List<NavItemSpec> = buildList {
                if (deviceOverviewDeviceInfoVm != null && deviceOverviewRemoteVm != null) {
                    add(NavItemSpec(NavPage.DEVICE_OVERVIEW, "nav_device_overview", Icons.Filled.Devices))
                }
                if (appConsoleVm != null) add(NavItemSpec(NavPage.APP_CONSOLE, "nav_app_console", Icons.Filled.Apps))
                if (logcatVm != null) add(NavItemSpec(NavPage.LOGCAT, "nav_logcat", Icons.AutoMirrored.Filled.Subject))
                if (systemOpsVm != null) add(NavItemSpec(NavPage.SYSTEM_OPS, "nav_system_ops", Icons.Filled.PowerSettingsNew))
                if (systemInfoVm != null) add(NavItemSpec(NavPage.SYSTEM_INFO, "nav_system_info", Icons.Filled.Memory))
                if (fileExplorerVm != null) add(NavItemSpec(NavPage.FILE_EXPLORER, "nav_file_explorer", Icons.Filled.FolderOpen))
            }
            navItems.forEach { (navPage, key, icon) ->
                NavItem(
                    label = Strings.t(key),
                    icon = icon,
                    selected = page == navPage,
                    onClick = { page = navPage },
                )
            }

            if (settingsVm != null && configDir != null) {
                Divider()
                NavItem(
                    label = Strings.t("nav_settings"),
                    icon = Icons.Filled.Settings,
                    selected = page == NavPage.SETTINGS,
                    onClick = { page = NavPage.SETTINGS },
                )
            }
        }
        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                page == NavPage.SETTINGS && settingsVm != null && configDir != null -> {
                    SettingsScreen(vm = settingsVm, configDir = configDir, scrcpyLocator = scrcpyLocator, repo = repo, adbLocator = adbLocator)
                }
                selected != null && page == NavPage.DEVICE_OVERVIEW && deviceOverviewDeviceInfoVm != null && deviceOverviewRemoteVm != null && scrcpyInstaller != null && scrcpyLocator != null && scrcpyLauncher != null -> {
                    DeviceOverviewScreen(
                        deviceInfoVm = deviceOverviewDeviceInfoVm,
                        remoteVm = deviceOverviewRemoteVm,
                        onOpenScreenshot = onOpenScreenshot,
                        screenshotLoading = screenshotLoading,
                        selectedSerial = selected,
                        scrcpyInstaller = scrcpyInstaller,
                        scrcpyLocator = scrcpyLocator,
                        scrcpyLauncher = scrcpyLauncher,
                        settingsVm = settingsVm,
                        systemOpsVm = systemOpsVm,
                        onOpenShell = onOpenShell,
                    )
                }
                selected != null && page == NavPage.APP_CONSOLE && appConsoleVm != null -> {
                    AppConsoleScreen(vm = appConsoleVm, selectedSerial = selected)
                }
                selected != null && page == NavPage.LOGCAT && logcatVm != null -> {
                    LogcatScreen(vm = logcatVm)
                }
                selected != null && page == NavPage.SYSTEM_OPS && systemOpsVm != null -> {
                    SystemOpsScreen(vm = systemOpsVm, selectedSerial = selected, onOpenConnect = { showConnect = true })
                }
                selected != null && page == NavPage.SYSTEM_INFO && systemInfoVm != null -> {
                    SystemInfoScreen(vm = systemInfoVm, selectedSerial = selected)
                }
                selected != null && page == NavPage.FILE_EXPLORER && fileExplorerVm != null -> {
                    FileExplorerScreen(vm = fileExplorerVm, selectedSerial = selected, onOpenConnect = { showConnect = true })
                }
                else -> EmptyState(
                    title = Strings.t("no_device_selected"),
                    hint = Strings.t("no_device_hint"),
                    icon = Icons.Filled.Devices,
                    actionLabel = Strings.t("connect_first_device"),
                    onAction = { showConnect = true },
                )
            }
        }
    }

    if (showConnect) {
        ConnectDialog(vm = vm, onDismiss = { showConnect = false })
    }
}

private enum class NavPage { DEVICE_OVERVIEW, APP_CONSOLE, LOGCAT, SYSTEM_OPS, SYSTEM_INFO, FILE_EXPLORER, SETTINGS }

private data class NavItemSpec(val page: NavPage, val labelKey: String, val icon: ImageVector)

/**
 * Sidebar nav row with a real selected state: primary-tinted background + a 3dp left indicator
 * bar + primary-colored icon/label. Replaces the old "append ' *' to the label" hack.
 * 44dp tall to meet the desktop touch-target floor.
 */
@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (selected) MaterialTheme.colors.primary.copy(alpha = 0.14f) else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(backgroundColor)
            .clickable(onClick = onClick),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(MaterialTheme.colors.primary)
                    .align(Alignment.CenterStart),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, tint = contentColor)
            Spacer(Modifier.width(12.dp))
            Text(label, color = contentColor, style = MaterialTheme.typography.body2)
        }
    }
}
