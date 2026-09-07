package com.adbgui.core.domain

data class PermissionInfo(
    val name: String,
    val granted: Boolean,
    val runtime: Boolean,
    val group: String? = null,
)

/** Parsed `adb shell dumpsys package <pkg>` — fields E (version/path/libs dir) and D (permissions) share this. */
data class DumpsysPackage(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val publicSourceDir: String?,
    val nativeLibraryDir: String?,
    val primaryCpuAbi: String?,
    val permissions: List<PermissionInfo>,
)

/** UI view for E (app detail): flat, no permissions, decoupled from parse output. */
data class PackageDetail(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val publicSourceDir: String?,
    val nativeLibraryDir: String?,
    val primaryCpuAbi: String?,
    val nativeLibs: List<String>,
)
