package com.adbgui.core.adb

import com.adbgui.core.domain.PairResult

object PairResultParser {
    fun parse(stdout: String, stderr: String, exitCode: Int): PairResult {
        val combined = "$stdout\n$stderr"
        // Success: "Successfully paired to 192.168.1.50:4321"
        if (combined.contains("Successfully paired")) {
            return PairResult(success = true, message = stdout.ifBlank { stderr })
        }
        // Failure: various messages — "cannot connect", "invalid code", "Failment", etc.
        val reason = combined.trim().ifBlank { "pair failed (exit $exitCode)" }
        return PairResult(success = false, message = reason)
    }
}
