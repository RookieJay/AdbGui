package com.adbgui.desktop.ui

/** A read-only system-info query shown on the System Info page.
 *  @param group      i18n key for the group heading (e.g. "si_group_apps").
 *  @param titleKey   i18n key for this command's display name.
 *  @param cmd         device-shell command template. `{pkg}` is substituted with the
 *                     selected package name by the VM. Pipes/redirects are interpreted
 *                     by the device's /system/bin/sh (the host does no shell parsing).
 *  @param needsPackage true if `cmd` contains `{pkg}` and requires a selected package. */
data class InfoCommand(
    val group: String,
    val titleKey: String,
    val cmd: String,
    val needsPackage: Boolean,
)

/** Curated, data-driven command list (spec §7.3). Add/remove here only — the UI renders
 *  this list, so adding a command never touches the screen. */
val systemInfoCommands: List<InfoCommand> = listOf(
    // 应用 / Apps
    InfoCommand("si_group_apps", "si_cmd_pm_path",
        "pm path {pkg}", needsPackage = true),
    InfoCommand("si_group_apps", "si_cmd_pkg_version",
        "dumpsys package {pkg} | grep -E \"versionName|versionCode\" || true", needsPackage = true),
    InfoCommand("si_group_apps", "si_cmd_pm_features",
        "pm list features", needsPackage = false),
    InfoCommand("si_group_apps", "si_cmd_pm_libraries",
        "pm list libraries", needsPackage = false),
    // 显示 / Display
    InfoCommand("si_group_display", "si_cmd_wm_density",
        "wm density", needsPackage = false),
    InfoCommand("si_group_display", "si_cmd_current_focus",
        "dumpsys window | grep mCurrentFocus || true", needsPackage = false),
    // 系统 / System
    InfoCommand("si_group_system", "si_cmd_getprop",
        "getprop", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_diskstats",
        "dumpsys diskstats", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_df",
        "df -h", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_meminfo",
        "dumpsys meminfo", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_meminfo_pkg",
        "dumpsys meminfo {pkg}", needsPackage = true),
    InfoCommand("si_group_system", "si_cmd_top",
        "top -n 1", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_cpuinfo",
        "cat /proc/cpuinfo", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_uptime",
        "uptime", needsPackage = false),
    // 网络 / Network
    InfoCommand("si_group_network", "si_cmd_ifconfig",
        "ifconfig", needsPackage = false),
    InfoCommand("si_group_network", "si_cmd_mac",
        "cat /sys/class/net/eth0/address 2>/dev/null || cat /sys/class/net/wlan0/address 2>/dev/null || true",
        needsPackage = false),
)
