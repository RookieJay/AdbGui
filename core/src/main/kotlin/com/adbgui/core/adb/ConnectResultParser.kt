package com.adbgui.core.adb

import com.adbgui.core.domain.ConnectResult

object ConnectResultParser {
    private val connected = Regex("connected to ([^\\s]+)")
    private val target = Regex("(?:connect to|connected to) ([0-9.]+:\\d+)")

    fun parse(stdout: String, stderr: String, exitCode: Int): ConnectResult {
        val combined = "$stdout\n$stderr"
        val targetSerial = target.find(combined)?.groupValues?.get(1)
        if (connected.containsMatchIn(combined)) {
            return ConnectResult(serial = targetSerial, success = true, message = stdout.ifBlank { stderr })
        }
        val reason = Regex("cannot connect to [0-9.]+:\\d+: (.+)").find(combined)?.groupValues?.get(1)
            ?: Regex("failed to connect to [0-9.]+:\\d+.*").find(combined)?.value
            ?: combined.trim().ifBlank { "adb connect failed (exit $exitCode)" }
        return ConnectResult(serial = targetSerial, success = false, message = reason)
    }
}
