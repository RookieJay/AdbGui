package com.adbgui.core.adb

import com.adbgui.core.domain.DeviceProps

object GetpropParser {
    private fun get(stdout: String, key: String): String? =
        Regex("\\[$key]: \\[([^]]*)]").find(stdout)?.groupValues?.get(1)

    fun parse(stdout: String, serial: String): DeviceProps {
        val brand = get(stdout, "ro.product.brand") ?: "unknown"
        val manufacturer = get(stdout, "ro.product.manufacturer") ?: "unknown"
        return DeviceProps(
            brand = brand,
            manufacturer = manufacturer,
            model = get(stdout, "ro.product.model") ?: "unknown",
            androidVersion = get(stdout, "ro.build.version.release") ?: "unknown",
            sdkInt = get(stdout, "ro.build.version.sdk")?.toIntOrNull() ?: 0,
            serial = serial,
            resolution = get(stdout, "ro.boot.resolution") ?: "unknown",
            abi = get(stdout, "ro.product.cpu.abi") ?: "unknown",
        )
    }
}
