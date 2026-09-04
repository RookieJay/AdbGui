package com.adbgui.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType { USB, WIRELESS }
enum class DeviceStatus { ONLINE, OFFLINE, UNAUTHORIZED, UNKNOWN }
enum class AdbSource { OVERRIDE, BUNDLED, PATH }

/**
 * How the device list is grouped in the sidebar. Stored in settings.json.
 * NONE = flat list, MRU-sorted.
 */
@Serializable
enum class DeviceGroupBy { NONE, TYPE, STATUS, SUBNET, TAG }

/**
 * Why an `adb connect` failed. Used by the UI to give an actionable hint instead of a
 * bare "Connection refused" — a stale port (device rebooted, wireless debugging port
 * randomized) and an unreachable host (device off / wrong IP) both warrant the same
 * guidance: check the phone's "Wireless debugging" screen for the current port, or just
 * open wireless debugging and let adb's mDNS auto-discovery surface the device.
 */
enum class ConnectFailureReason { PORT_STALE, UNREACHABLE, OTHER }

data class DeviceSnapshot(val serial: String, val status: DeviceStatus)

data class DeviceView(
    val serial: String,
    val status: DeviceStatus,
    val alias: String? = null,
    val type: DeviceType? = null,
    val wirelessIp: String? = null,
    val wirelessPort: Int? = null,
    val lastConnectedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val tag: String? = null,
) {
    val isLive: Boolean get() = status == DeviceStatus.ONLINE
}

data class PackageInfo(val name: String, val isSystem: Boolean)

data class AdbBinary(val path: String, val source: AdbSource)

data class ConnectResult(
    val serial: String?,
    val success: Boolean,
    val message: String,
    val reason: ConnectFailureReason? = null,
)

data class InstallResult(val success: Boolean, val message: String, val code: String? = null)

/** Result of `adb -s <serial> bugreport <destDir>`: the zip path (parsed from stdout or
 *  scanned from destDir) plus the raw stdout (for UI display / debugging). */
data class BugreportResult(val zipPath: String, val stdout: String)

/** Flags for `adb install` / `adb install-multiple`. Each maps to a standard adb switch. */
data class InstallFlags(
    val reinstall: Boolean,   // -r  keep data
    val allowTest: Boolean,   // -t  test APK
    val downgrade: Boolean,   // -d  allow downgrade
    val grantPerms: Boolean,  // -g  grant all runtime perms
)

data class DeviceProps(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val serial: String,
    val resolution: String,
    val abi: String,
)
