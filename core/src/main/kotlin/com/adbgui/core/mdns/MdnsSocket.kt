package com.adbgui.core.mdns

/** mDNS UDP socket 的可测抽象（spec §4.1）。实现负责：绑定 5353（SO_REUSEADDR 与 adb server
 *  共存，2026-10-23 实测可行）、在所有 up+multicast IPv4 接口 join 组播组、按接口发送查询。 */
interface MdnsSocket {
    /** 发一条 PTR 查询 "<serviceType>.local" 到 224.0.0.251:5353。 */
    suspend fun sendQuery(serviceType: String)
    /** 收一个包，超时返回 null。socket 由实现保证已 join 组播组。 */
    suspend fun receive(timeoutMs: Int): ByteArray?
    fun close()
}
