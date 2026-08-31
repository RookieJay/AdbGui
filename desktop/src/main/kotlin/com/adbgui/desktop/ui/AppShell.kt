package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.LocalIndication
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.DeviceView
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.theme.AppColors
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
    portForwardingVm: PortForwardingViewModel? = null,
    selectedSerial: MutableStateFlow<String?>? = null,
    onOpenShell: (String) -> Unit = {},
    repo: com.adbgui.core.device.DeviceRepository? = null,
    adbLocator: com.adbgui.core.adb.AdbLocator? = null,
) {
    var page by remember { mutableStateOf(NavPage.DEVICE_OVERVIEW) }
    var showConnect by remember { mutableStateOf(false) }
    val serialFlow = selectedSerial ?: remember { MutableStateFlow<String?>(null) }
    val selected by serialFlow.collectAsState()
    val devices by vm.devices.collectAsState()
    val selectedDevice = devices.firstOrNull { it.serial == selected }
    val dividerColor = AppColors.current.divider

    Row(modifier = modifier.fillMaxSize()) {
        // ---- Sidebar: device list | feature nav | settings, as three separated zones ----
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
            Divider(color = dividerColor)

            // Feature nav zone — fixed (does not scroll with the device list).
            Text(
                Strings.t("nav_section_features"),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
            )
            val navItems: List<NavItemSpec> = buildList {
                if (deviceOverviewDeviceInfoVm != null && deviceOverviewRemoteVm != null) {
                    add(NavItemSpec(NavPage.DEVICE_OVERVIEW, "nav_device_overview", Icons.Filled.Devices))
                }
                if (appConsoleVm != null) add(NavItemSpec(NavPage.APP_CONSOLE, "nav_app_console", Icons.Filled.Apps))
                if (logcatVm != null) add(NavItemSpec(NavPage.LOGCAT, "nav_logcat", Icons.AutoMirrored.Filled.Subject))
                if (systemInfoVm != null) add(NavItemSpec(NavPage.SYSTEM_INFO, "nav_system_info", Icons.Filled.Memory))
                if (fileExplorerVm != null) add(NavItemSpec(NavPage.FILE_EXPLORER, "nav_file_explorer", Icons.Filled.FolderOpen))
                if (portForwardingVm != null) add(NavItemSpec(NavPage.PORT_FORWARDING, "nav_port_forwarding", Icons.Filled.CompareArrows))
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
                Divider(color = dividerColor)
                NavItem(
                    label = Strings.t("nav_settings"),
                    icon = Icons.Filled.Settings,
                    selected = page == NavPage.SETTINGS,
                    onClick = { page = NavPage.SETTINGS },
                )
            }
        }
        Divider(color = dividerColor, modifier = Modifier.fillMaxHeight().width(1.dp))

        // ---- Content: top bar + page ----
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                pageTitle = pageTitle(page),
                device = selectedDevice,
            )
            Divider(color = dividerColor)
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
                    selected != null && page == NavPage.SYSTEM_INFO && systemInfoVm != null -> {
                        SystemInfoScreen(vm = systemInfoVm, selectedSerial = selected)
                    }
                    selected != null && page == NavPage.FILE_EXPLORER && fileExplorerVm != null -> {
                        FileExplorerScreen(vm = fileExplorerVm, selectedSerial = selected, onOpenConnect = { showConnect = true })
                    }
                    selected != null && page == NavPage.PORT_FORWARDING && portForwardingVm != null -> {
                        PortForwardingScreen(vm = portForwardingVm, selectedSerial = selected)
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
    }

    if (showConnect) {
        ConnectDialog(vm = vm, onDismiss = { showConnect = false })
    }
}

private fun pageTitle(page: NavPage): String = when (page) {
    NavPage.DEVICE_OVERVIEW -> Strings.t("nav_device_overview")
    NavPage.APP_CONSOLE -> Strings.t("nav_app_console")
    NavPage.LOGCAT -> Strings.t("nav_logcat")
    NavPage.SYSTEM_INFO -> Strings.t("nav_system_info")
    NavPage.FILE_EXPLORER -> Strings.t("nav_file_explorer")
    NavPage.PORT_FORWARDING -> Strings.t("nav_port_forwarding")
    NavPage.SETTINGS -> Strings.t("nav_settings")
}

private enum class NavPage { DEVICE_OVERVIEW, APP_CONSOLE, LOGCAT, SYSTEM_INFO, FILE_EXPLORER, PORT_FORWARDING, SETTINGS }

private data class NavItemSpec(val page: NavPage, val labelKey: String, val icon: ImageVector)

/**
 * Persistent top bar over the content area: current page title on the left, selected device
 * (status dot + alias/serial) on the right. Gives spatial context the per-page titles lacked.
 */
@Composable
private fun TopBar(
    pageTitle: String,
    device: DeviceView?,
) {
    val dividerColor = AppColors.current.divider
    Surface(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        color = MaterialTheme.colors.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pageTitle, style = MaterialTheme.typography.h6)
            Spacer(Modifier.width(16.dp))
            device?.let { d ->
                // Device context on the right; weight spacer pushes it to the end.
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(isLive = d.isLive)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        d.alias ?: d.serial,
                        style = MaterialTheme.typography.subtitle2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                    )
                }
            } ?: run {
                Spacer(Modifier.weight(1f))
                Text(
                    Strings.t("no_device_selected"),
                    style = MaterialTheme.typography.subtitle2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
    // Divider drawn by the caller (AppShell) so it spans the full content width including under
    // any nested surfaces; kept here as a no-op to avoid a stray second line.
}

/**
 * Sidebar nav row: selected = primary-tinted background + 3dp left indicator + primary icon/label
 * (SemiBold); hovered = subtle onSurface wash so desktop users get a hover affordance (the M2
 * ripple means nothing on desktop). 44dp tall to meet the desktop touch-target floor.
 */
@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val backgroundColor = when {
        selected -> MaterialTheme.colors.primary.copy(alpha = 0.14f)
        hovered -> MaterialTheme.colors.onSurface.copy(alpha = 0.08f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val contentColor = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusBorder = if (focused) Modifier.border(1.5.dp, MaterialTheme.colors.primary) else Modifier
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(backgroundColor)
            .then(focusBorder)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> hovered = false
                            else -> {}
                        }
                    }
                }
            }
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar) {
                    onClick(); true
                } else false
            },
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
            // Icon sits beside a visible label → decorative (icon-context).
            Icon(icon, contentDescription = null, tint = contentColor)
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                color = contentColor,
                style = MaterialTheme.typography.subtitle2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
