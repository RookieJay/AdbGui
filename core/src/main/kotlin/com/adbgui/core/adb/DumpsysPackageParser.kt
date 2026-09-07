package com.adbgui.core.adb

import com.adbgui.core.domain.DumpsysPackage
import com.adbgui.core.domain.PermissionInfo

/** Parse `adb shell dumpsys package <pkg>` stdout into [DumpsysPackage].
 *  Returns null when no `Package [` / `Packages:` section is found (caller wraps into
 *  AdbCommandException with raw stdout). Field names vary across Android versions:
 *  - Android <=9 uses `legacyNativeLibraryDir=` + `resourcePath=`; Android >=10 uses `nativeLibraryDir=` + `publicSourceDir=`.
 *  - `versionCode=` may carry trailing ` minSdk=.. targetSdk=..`; extract leading int.
 *  - `primaryCpuAbi=` may be literal "null".
 *  Permissions: `install permissions:` and `runtime permissions:` blocks share the
 *  `<perm>: granted=<bool>` line format -- a line-oriented state machine tags each perm
 *  runtime=true/false by the section it appears in. `requested permissions:` has no
 *  granted state and is skipped. Runtime perm lines may also carry trailing
 *  `, flags=[ ... ]` after the granted boolean (Android 9 shared-user perms) -- the
 *  regex tolerates this rather than requiring end-of-line after the boolean. */
object DumpsysPackageParser {
    fun parse(stdout: String): DumpsysPackage? {
        if (!stdout.contains("Package [") && !stdout.contains("Packages:")) return null
        val versionName = findVal(stdout, "versionName")
        val versionCode = findVal(stdout, "versionCode")
            ?.let { vc -> Regex("\\d+").find(vc)?.value?.toLongOrNull() }
        val codePath = findVal(stdout, "codePath")
        val publicSourceDir = findVal(stdout, "publicSourceDir") ?: findVal(stdout, "resourcePath")
        val nativeLibraryDir = findVal(stdout, "nativeLibraryDir") ?: findVal(stdout, "legacyNativeLibraryDir")
        val primaryCpuAbi = findVal(stdout, "primaryCpuAbi")?.let { if (it == "null") null else it }
        val permissions = parsePermissions(stdout)
        return DumpsysPackage(versionName, versionCode, codePath, publicSourceDir, nativeLibraryDir, primaryCpuAbi, permissions)
    }

    private fun findVal(stdout: String, key: String): String? {
        val re = Regex("(?m)^\\s*$key=(.*)$")
        return re.find(stdout)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")?.ifBlank { null }
    }

    private enum class PermSection { NONE, REQUESTED, INSTALL, RUNTIME }

    private fun parsePermissions(stdout: String): List<PermissionInfo> {
        val result = mutableListOf<PermissionInfo>()
        var section = PermSection.NONE
        // Matches `  <perm>: granted=<true|false>` possibly followed by `, flags=[ ... ]`.
        // No end-of-line anchor after the boolean: Android 9 runtime perms carry trailing flags.
        val permLine = Regex("^\\s+(\\S+): granted=(true|false)")
        stdout.lineSequence().forEach { line ->
            when {
                line.contains("install permissions:") -> section = PermSection.INSTALL
                line.contains("runtime permissions:") -> section = PermSection.RUNTIME
                line.contains("requested permissions:") -> section = PermSection.REQUESTED
                else -> {
                    val m = permLine.find(line)
                    if (m != null && section != PermSection.NONE && section != PermSection.REQUESTED) {
                        val name = m.groupValues[1]
                        val granted = m.groupValues[2] == "true"
                        val runtime = section == PermSection.RUNTIME
                        if (result.none { it.name == name }) {
                            result.add(PermissionInfo(name = name, granted = granted, runtime = runtime))
                        }
                    }
                }
            }
        }
        return result
    }
}
