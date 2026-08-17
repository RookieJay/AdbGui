package com.adbgui.core.adb

import com.adbgui.core.domain.LogcatLine
import com.adbgui.core.domain.LogcatLevel

object LogcatLineParser {
    // 08-17 10:23:45.123  1234  5678 I ActivityManager: Display changed
    private val re = Regex(
        """(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]*):\s?(.*)"""
    )

    fun parse(line: String): LogcatLine? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("----")) return null
        val m = re.matchEntire(trimmed) ?: return null
        return LogcatLine(
            raw = line,
            timestamp = m.groupValues[1],
            pid = m.groupValues[2].toIntOrNull() ?: 0,
            tid = m.groupValues[3].toIntOrNull() ?: 0,
            level = levelOf(m.groupValues[4].first()) ?: LogcatLevel.V,
            tag = m.groupValues[5].trim(),
            message = m.groupValues[6],
        )
    }

    private fun levelOf(c: Char): LogcatLevel? = when (c) {
        'V' -> LogcatLevel.V; 'D' -> LogcatLevel.D; 'I' -> LogcatLevel.I
        'W' -> LogcatLevel.W; 'E' -> LogcatLevel.E; 'F' -> LogcatLevel.F
        else -> null
    }
}
