package com.adbgui.desktop.ui.i18n

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

object Strings {
    var current: Locale by mutableStateOf(Locale.ZH)
        private set

    // Safe to call from any thread (e.g. the background startup coroutine / ViewModel scopes):
    // wrap the write in a snapshot so the mutation is applied and readable cross-thread.
    fun set(locale: Locale) {
        Snapshot.withMutableSnapshot { current = locale }
    }

    private val zh: Map<String, String> = mapOf(
        // App title
        "app_title" to "ADB GUI",
        // Navigation
        "nav_device_info" to "设备信息",
        "nav_screenshot" to "截图",
        "nav_app_manager" to "应用管理",
        "nav_back_to_devices" to "返回设备列表",
        "nav_settings" to "设置",
        // Common
        "no_device_selected" to "未选择设备",
        "ok" to "确定",
        "cancel" to "取消",
        "save" to "保存",
        "refresh" to "刷新",
        "clear" to "清除",
        "apply" to "应用",
        "browse" to "浏览",
        "change" to "更改",
        "export" to "导出",
        "open" to "打开",
        "open_folder" to "打开文件夹",
        "collapse" to "折叠",
        "expand" to "展开",
        "adb_error" to "adb 错误",
        // Device list
        "devices" to "设备",
        "reconnect" to "重新连接",
        "rename" to "重命名",
        "disconnect" to "断开连接",
        "forget" to "移除",
        "forget_confirm_title" to "移除设备？",
        "forget_confirm_body" to "从历史记录中移除 \"%s\"？设备本身不受影响。",
        // Connect dialog
        "connect_title" to "连接设备",
        "ip_address" to "IP 地址",
        "port" to "端口",
        "connect" to "连接",
        // Settings
        "settings" to "设置",
        "adb_binary_path" to "ADB 二进制路径",
        "adb_path_override_label" to "adb 路径覆盖",
        "adb_path_placeholder" to "（使用内置 / 系统 PATH）",
        "select_adb_binary" to "选择 adb 二进制文件",
        "language" to "语言",
        "log_level" to "日志级别",
        "current" to "当前：%s",
        "logs" to "日志",
        "open_logs_folder" to "打开日志文件夹",
        "save_logs_zip" to "保存日志压缩包",
        "export_logs" to "导出日志",
        "status_adb_path_set" to "adb 路径已设置：%s",
        "status_adb_cleared" to "adb 路径覆盖已清除",
        "status_log_level_set" to "日志级别已设置：%s",
        "status_open_failed" to "打开失败：%s",
        "status_no_logs" to "没有可导出的日志",
        "status_exported_to" to "已导出至 %s",
        "status_export_failed" to "导出失败：%s",
        // App Manager
        "app_manager" to "应用管理",
        "select_apk" to "选择 APK",
        "no_packages" to "没有应用。请按刷新。",
        "system" to "系统",
        "uninstall" to "卸载",
        // Device Info
        "device_info" to "设备信息",
        "export_device_info_title" to "导出设备信息",
        "report_ready" to "报告已就绪（%d 字符）",
        "status_save_failed" to "保存失败：%s",
        "saved_path" to "已保存：%s",
        "open_image" to "打开图片",
        "no_device_selected_refresh" to "未选择设备。请按刷新。",
        "prop_model" to "型号",
        "prop_android_version" to "Android 版本",
        "prop_sdk" to "SDK",
        "prop_serial" to "序列号",
        "prop_resolution" to "分辨率",
        "prop_abi" to "ABI",
        // Screenshot
        "screenshot" to "截图",
        "capture" to "截图",
        "save_screenshot_title" to "保存截图",
        "no_screenshot" to "暂无截图。请按截图。",
        "content_screenshot" to "设备截图",
        "decode_failed" to "图片解码失败（%d 字节）",
        // Device Info ViewModel (export report)
        "report_summary_header" to "===== 摘要 =====",
        "report_export_header" to "设备信息导出",
        "report_generated" to "生成时间：%s",
        // Logcat
        "logcat" to "Logcat",
        "nav_logcat" to "日志",
        "level" to "级别",
        "level_all" to "全部",
        "tag_include" to "Tag 包含",
        "tag_exclude" to "Tag 排除",
        "text_search" to "文本搜索",
        "pid" to "PID",
        "pause" to "暂停",
        "resume" to "恢复",
        "copy" to "复制",
        "reconnecting_logcat" to "正在重连 logcat…",
        "no_device_selected_logcat" to "未选择设备",
        "save_logcat_title" to "导出 logcat",
        // System Ops
        "system_ops" to "系统操作",
        "nav_system_ops" to "系统操作",
        "reboot" to "重启",
        "reboot_normal" to "重启",
        "reboot_recovery" to "重启到恢复",
        "reboot_bootloader" to "重启到引导",
        "reboot_sideload" to "重启到侧载",
        "root_op" to "Root",
        "remount_op" to "Remount",
        "reboot_confirm_title" to "确认重启？",
        "reboot_confirm_body" to "将重启到 %s，设备会短暂断连。",
        // Wireless Pairing
        "pair" to "配对",
        "pair_title" to "无线配对",
        "pairing_code" to "配对码",
        "pair_hint" to "在设备的「无线调试」中获取 IP:端口 和配对码",
        "pairing" to "配对中…",
        // Shell
        "shell" to "Shell",
        "nav_shell" to "Shell",
        "open_shell" to "打开 Shell",
    )

    private val en: Map<String, String> = mapOf(
        // App title
        "app_title" to "ADB GUI",
        // Navigation
        "nav_device_info" to "Device Info",
        "nav_screenshot" to "Screenshot",
        "nav_app_manager" to "App Manager",
        "nav_back_to_devices" to "Back to devices",
        "nav_settings" to "Settings",
        // Common
        "no_device_selected" to "No device selected",
        "ok" to "OK",
        "cancel" to "Cancel",
        "save" to "Save",
        "refresh" to "Refresh",
        "clear" to "Clear",
        "apply" to "Apply",
        "browse" to "Browse",
        "change" to "Change",
        "export" to "Export",
        "open" to "Open",
        "open_folder" to "Open folder",
        "collapse" to "Collapse",
        "expand" to "Expand",
        "adb_error" to "adb error",
        // Device list
        "devices" to "Devices",
        "reconnect" to "Reconnect",
        "rename" to "Rename",
        "disconnect" to "Disconnect",
        "forget" to "Forget",
        "forget_confirm_title" to "Forget device?",
        "forget_confirm_body" to "Remove \"%s\" from history? The device itself is unaffected.",
        // Connect dialog
        "connect_title" to "Connect to device",
        "ip_address" to "IP address",
        "port" to "Port",
        "connect" to "Connect",
        // Settings
        "settings" to "Settings",
        "adb_binary_path" to "ADB binary path",
        "adb_path_override_label" to "adb path override",
        "adb_path_placeholder" to "(use bundled / system PATH)",
        "select_adb_binary" to "Select adb binary",
        "language" to "Language",
        "log_level" to "Log level",
        "current" to "Current: %s",
        "logs" to "Logs",
        "open_logs_folder" to "Open logs folder",
        "save_logs_zip" to "Save logs zip",
        "export_logs" to "Export logs",
        "status_adb_path_set" to "adb path set: %s",
        "status_adb_cleared" to "adb override cleared",
        "status_log_level_set" to "log level set: %s",
        "status_open_failed" to "open failed: %s",
        "status_no_logs" to "no logs to export",
        "status_exported_to" to "exported to %s",
        "status_export_failed" to "Export failed: %s",
        // App Manager
        "app_manager" to "App Manager",
        "select_apk" to "Select APK",
        "no_packages" to "No packages. Press Refresh.",
        "system" to "system",
        "uninstall" to "Uninstall",
        // Device Info
        "device_info" to "Device Info",
        "export_device_info_title" to "Export device info",
        "report_ready" to "Report ready (%d chars)",
        "status_save_failed" to "Save failed: %s",
        "saved_path" to "Saved: %s",
        "open_image" to "Open image",
        "no_device_selected_refresh" to "No device selected. Press Refresh.",
        "prop_model" to "Model",
        "prop_android_version" to "Android version",
        "prop_sdk" to "SDK",
        "prop_serial" to "Serial",
        "prop_resolution" to "Resolution",
        "prop_abi" to "ABI",
        // Screenshot
        "screenshot" to "Screenshot",
        "capture" to "Capture",
        "save_screenshot_title" to "Save screenshot",
        "no_screenshot" to "No screenshot yet. Press Capture.",
        "content_screenshot" to "Device screenshot",
        "decode_failed" to "Failed to decode image (%d bytes)",
        // Device Info ViewModel (export report)
        "report_summary_header" to "===== Summary =====",
        "report_export_header" to "Device Info Export",
        "report_generated" to "Generated: %s",
        // Logcat
        "logcat" to "Logcat",
        "nav_logcat" to "Logcat",
        "level" to "Level",
        "level_all" to "All",
        "tag_include" to "Tag includes",
        "tag_exclude" to "Tag excludes",
        "text_search" to "Text search",
        "pid" to "PID",
        "pause" to "Pause",
        "resume" to "Resume",
        "copy" to "Copy",
        "reconnecting_logcat" to "Reconnecting logcat…",
        "no_device_selected_logcat" to "No device selected",
        "save_logcat_title" to "Export logcat",
        // System Ops
        "system_ops" to "System Ops",
        "nav_system_ops" to "System Ops",
        "reboot" to "Reboot",
        "reboot_normal" to "Reboot",
        "reboot_recovery" to "Reboot to Recovery",
        "reboot_bootloader" to "Reboot to Bootloader",
        "reboot_sideload" to "Reboot to Sideload",
        "root_op" to "Root",
        "remount_op" to "Remount",
        "reboot_confirm_title" to "Confirm Reboot?",
        "reboot_confirm_body" to "Will reboot to %s; the device disconnects briefly.",
        // Wireless Pairing
        "pair" to "Pair",
        "pair_title" to "Wireless Pairing",
        "pairing_code" to "Pairing code",
        "pair_hint" to "Get the IP:port and pairing code from the device's \"Wireless debugging\" screen",
        "pairing" to "Pairing…",
        // Shell
        "shell" to "Shell",
        "nav_shell" to "Shell",
        "open_shell" to "Open Shell",
    )

    private val maps = mapOf(Locale.ZH to zh, Locale.EN to en)

    fun t(key: String): String = (maps[current] ?: zh)[key] ?: en[key] ?: key
}
