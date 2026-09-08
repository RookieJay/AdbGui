package com.adbgui.desktop.ui.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 全局统一的时间展示格式：yyyy-MM-dd HH:mm:ss（本地时区）。
 * 解析存储的 ISO-8601 instant（如 2026-09-08T10:30:45.123Z）→ 本地时间友好格式。
 * 输入 null 返回 null（调用方走"从未"兜底）；解析失败返回原字符串（不静默吞，保留原文兜底）。
 */
private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

fun formatTimestamp(iso: String?): String? {
    if (iso == null) return null
    return runCatching { TIMESTAMP_FORMAT.format(Instant.parse(iso)) }.getOrDefault(iso)
}
