package com.adbgui.core.adb

import com.adbgui.core.domain.InstallResult
import com.adbgui.core.domain.PackageInfo

object PackageListParser {
    private val line = Regex("package:(\\S+)")

    fun parse(stdout: String, thirdPartyOnly: Boolean): List<PackageInfo> =
        line.findAll(stdout).map { PackageInfo(name = it.groupValues[1], isSystem = !thirdPartyOnly) }.toList()
}

object InstallResultParser {
    private val failure = Regex("Failure\\s*\\[([^]]+)]")

    fun parse(stdout: String, stderr: String, exitCode: Int): InstallResult {
        val combined = "$stdout\n$stderr"
        if (combined.contains("Success")) return InstallResult(success = true, message = "Success")
        failure.find(combined)?.let {
            return InstallResult(success = false, message = it.groupValues[1], code = it.groupValues[1])
        }
        return InstallResult(success = false, message = combined.trim().ifBlank { "install failed (exit $exitCode)" })
    }
}
