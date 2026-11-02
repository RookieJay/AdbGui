package com.adbgui.core.domain

/** 一个 mDNS 发现的 adb 服务（spec 2026-10-23 §4.1/§4.2）。host/port 来自 SRV+A。 */
data class MdnsService(
    /** 实例名，如 "adb-af199bb4-Q9dhj"（标准无线调试，含 serial）或 "adb-unidentified"（老式 TCP adb）。 */
    val instanceName: String,
    /** 服务类型："_adb-tls-connect._tcp"（Android 11+ 无线调试）或 "_adb._tcp"（老式）。 */
    val serviceType: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
) {
    /** 标准实例名 "adb-<serial>-<suffix>" 里的真实 serial；提取不出返回 null。 */
    val deviceSerial: String? get() =
        if (instanceName.startsWith("adb-") && instanceName.count { it == '-' } >= 2)
            instanceName.removePrefix("adb-").substringBeforeLast('-')
        else null
}
