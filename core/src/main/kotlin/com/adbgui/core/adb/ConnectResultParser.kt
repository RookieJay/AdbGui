package com.adbgui.core.adb

import com.adbgui.core.domain.ConnectFailureReason
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
        val reason = combined.lowercase().let { lc ->
            when {
                // "Connection refused" / "Connection reset" → host is up but nothing is
                // listening on that port: the wireless-debugging connect port changed
                // (reboot / re-enable randomizes it), so the stored ip:port is stale.
                // Winsock code 10061 (WSAECONNREFUSED) is the same case on Windows and is
                // locale-independent — the OS error text is localized (e.g. Chinese
                // "由于目标计算机积极拒绝"), so the English substring alone isn't reliable.
                lc.contains("connection refused") || lc.contains("connection reset") ||
                    lc.contains("10061") || lc.contains("积极拒绝") ->
                    ConnectFailureReason.PORT_STALE
                // "timed out" / "network is unreachable" / "host unreachable" → the device
                // itself is off or the IP is wrong, not just a stale port. Winsock codes:
                // 10060 (WSAETIMEDOUT), 10051 (WSAENETUNREACH), 10065 (WSAEHOSTUNREACH).
                lc.contains("timed out") || lc.contains("unreachable") ||
                    lc.contains("10060") || lc.contains("10051") || lc.contains("10065") ->
                    ConnectFailureReason.UNREACHABLE
                else -> ConnectFailureReason.OTHER
            }
        }
        val reasonText = Regex("cannot connect to [0-9.]+:\\d+: (.+)").find(combined)?.groupValues?.get(1)
            ?: Regex("failed to connect to [0-9.]+:\\d+.*").find(combined)?.value
            ?: combined.trim().ifBlank { "adb connect failed (exit $exitCode)" }
        return ConnectResult(serial = targetSerial, success = false, message = reasonText, reason = reason)
    }
}
