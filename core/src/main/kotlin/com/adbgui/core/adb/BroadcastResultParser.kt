package com.adbgui.core.adb

import com.adbgui.core.domain.BroadcastResult

object BroadcastResultParser {
    fun parse(stdout: String, stderr: String, exitCode: Int): BroadcastResult {
        val combined = "$stdout\n$stderr"
        if (combined.contains("Broadcasting Intent")) {
            return BroadcastResult(true, stdout.ifBlank { stderr })
        }
        val msg = combined.trim().ifBlank { "broadcast failed (exit $exitCode)" }
        return BroadcastResult(false, msg)
    }
}
