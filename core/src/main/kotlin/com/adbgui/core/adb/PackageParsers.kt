package com.adbgui.core.adb

import com.adbgui.core.domain.InstallResult
import com.adbgui.core.domain.PackageInfo

object PackageListParser {
    // `pm list packages -f` format: "package:/data/app/com.example-xxx/base.apk=com.example"
    // `pm list packages -s` format: "package:com.example" (plain, no path)
    // NOTE: path may contain Base64 "==" (e.g. com.dangbeimarket-Q70KvGAKH3s15xDJwIo89A==),
    //       so the path/name split must use the LAST "=", not the first.
    private val plain = Regex("""^package:(\S+)$""", RegexOption.MULTILINE)
    // Greedy (.+) extends to the LAST "=" before (\S+)$ — correctly handles "==" in paths.
    private val combined = Regex("""^package:(?:(.+)=)?(\S+)$""", RegexOption.MULTILINE)

    private val SYSTEM_PREFIXES = arrayOf("/system/", "/vendor/", "/product/", "/odm/", "/oem/")

    /**
     * Parse two adb outputs into a single deduplicated package list.
     * - [fullOut]: `pm list packages -f` (all packages, with APK path).
     * - [sysOut]:  `pm list packages -s` (system packages, plain format).
     * A package is `isSystem = true` iff its name appears in [sysOut] OR its APK path starts with
     * a system partition prefix. The [sysOut] set is the authoritative source for system apps —
     * it correctly handles `UPDATED_SYSTEM_APP` (APK in /data/app/ but Android still treats it as
     * a system app, so path-only detection would get it wrong).
     */
    fun parse(fullOut: String, sysOut: String): List<PackageInfo> {
        val sysNames = plain.findAll(sysOut).map { it.groupValues[1] }.toSet()
        return combined.findAll(fullOut)
            .map { m ->
                val apkPath = m.groupValues[1]  // "" for plain format
                val name    = m.groupValues[2]
                val isSystem = name in sysNames ||
                    (apkPath.isNotEmpty() && SYSTEM_PREFIXES.any { apkPath.startsWith(it) })
                PackageInfo(name = name, isSystem = isSystem)
            }
            .sortedWith(compareBy({ it.isSystem }, { it.name }))
            .toList()
    }

    /** Single-output parse (test convenience / legacy plain format — everything marked non-system). */
    fun parse(stdout: String): List<PackageInfo> =
        plain.findAll(stdout)
            .map { PackageInfo(name = it.groupValues[1], isSystem = false) }
            .sortedWith(compareBy({ it.isSystem }, { it.name }))
            .toList()
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
