package com.adbgui.core.device

import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.domain.DeviceView

/** 纯函数：tracker 实时列表 + 历史条目 → 展示视图。spec §4.4：以 deviceId ?: serial 为身份分组，
 *  同组折叠为一条（代表条目 = 在线 serial 优先，否则最新 lastUsedAt/lastConnectedAt）。 */
object DeviceMerge {

    fun merge(live: List<DeviceSnapshot>, hist: List<DeviceHistoryEntry>): List<DeviceView> {
        // Group by deviceId when present, else by serial — preserves legacy per-serial semantics
        // for entries without a deviceId (regression red line for existing DeviceRepositoryTest).
        val groups = mutableMapOf<String, MutableList<DeviceHistoryEntry>>()
        val groupOrder = mutableListOf<String>()       // insertion order for deterministic output
        hist.forEach { e ->
            val key = e.deviceId ?: e.serial
            val bucket = groups[key]
            if (bucket == null) {
                groups[key] = mutableListOf(e)
                groupOrder += key
            } else {
                bucket += e
            }
        }

        val liveCovered = mutableSetOf<String>()
        val views = groupOrder.map { key ->
            val entries = groups.getValue(key)
            // representative = online entry first (its serial can receive adb commands);
            // else the most-recently-used entry (lastUsedAt, falling back to lastConnectedAt).
            val onlineEntry = entries.firstOrNull { e ->
                live.any { it.serial == e.serial && it.status == DeviceStatus.ONLINE }
            }
            val rep = onlineEntry
                ?: entries.maxByOrNull { it.lastUsedAt ?: it.lastConnectedAt ?: 0L }!!
            entries.forEach { liveCovered += it.serial }
            DeviceView(
                serial = rep.serial,
                status = live.firstOrNull { it.serial == rep.serial }?.status ?: DeviceStatus.OFFLINE,
                alias = entries.firstNotNullOfOrNull { it.alias },
                type = if (entries.any { it.type == DeviceType.WIRELESS }) DeviceType.WIRELESS
                else entries.firstOrNull { it.type != null }?.type,
                wirelessIp = rep.wirelessIp,
                wirelessPort = rep.wirelessPort,
                lastConnectedAt = entries.mapNotNull { it.lastConnectedAt }.maxOrNull(),
                lastUsedAt = entries.mapNotNull { it.lastUsedAt }.maxOrNull(),
                tag = entries.firstNotNullOfOrNull { it.tag },
            )
        }
        // Live-only devices (serial not covered by any history group) → bare views appended.
        val extra = live.filter { it.serial !in liveCovered }.map { snap ->
            DeviceView(serial = snap.serial, status = snap.status)
        }
        return views + extra
    }
}
