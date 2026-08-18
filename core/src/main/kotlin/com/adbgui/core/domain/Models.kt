package com.adbgui.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType { USB, WIRELESS }
enum class DeviceStatus { ONLINE, OFFLINE, UNAUTHORIZED, UNKNOWN }
enum class AdbSource { OVERRIDE, BUNDLED, PATH }

data class DeviceSnapshot(val serial: String, val status: DeviceStatus)

data class DeviceView(
    val serial: String,
    val status: DeviceStatus,
    val alias: String? = null,
    val type: DeviceType? = null,
    val wirelessIp: String? = null,
    val wirelessPort: Int? = null,
    val lastConnectedAt: Long? = null,
) {
    val isLive: Boolean get() = status == DeviceStatus.ONLINE
}

data class PackageInfo(val name: String, val isSystem: Boolean)

data class AdbBinary(val path: String, val source: AdbSource)

data class ConnectResult(val serial: String?, val success: Boolean, val message: String)

data class InstallResult(val success: Boolean, val message: String, val code: String? = null)

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
