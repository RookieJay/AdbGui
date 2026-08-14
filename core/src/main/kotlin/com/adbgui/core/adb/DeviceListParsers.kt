package com.adbgui.core.adb

import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus

object TrackDevicesParser {
    private const val HEADER = "List of devices attached"

    fun parseEvents(line: String): DeviceSnapshot? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == HEADER) return null
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 2) return null
        val serial = parts[0]
        val status = mapStatus(parts[1])
        return DeviceSnapshot(serial, status)
    }
}

object DevicesListParser {
    fun parse(stdout: String): List<DeviceSnapshot> {
        return stdout.lineSequence()
            .mapNotNull { TrackDevicesParser.parseEvents(it) }
            .toList()
    }
}

private fun mapStatus(raw: String): DeviceStatus = when (raw) {
    "device" -> DeviceStatus.ONLINE
    "offline" -> DeviceStatus.OFFLINE
    "unauthorized" -> DeviceStatus.UNAUTHORIZED
    else -> DeviceStatus.UNKNOWN
}
