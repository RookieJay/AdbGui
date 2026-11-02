package com.adbgui.core.mdns

/** mDNS UDP socket 的可测抽象（spec §4.1）。实现负责：绑定 5353（SO_REUSEADDR 与 adb server
 *  共存，2026-10-23 实测可行）、在所有 up+multicast IPv4 接口 join 组播组、按接口发送查询。
 *
 *  另有 [probeUnicast]：跨子网时组播 224.0.0.251 不过路由（2026-11-02 实测 PC 10.0.4.x 与
 *  手机 10.0.5.x 互不可达），legacy unicast 查询是唯一可用发现通道。 */
interface MdnsSocket {
    /** 发一条 PTR 查询 "<serviceType>.local" 到 224.0.0.251:5353。 */
    suspend fun sendQuery(serviceType: String)
    /** 收一个包，超时返回 null。socket 由实现保证已 join 组播组。 */
    suspend fun receive(timeoutMs: Int): ByteArray?
    /** legacy unicast 查询（RFC 6762 §18.1）：临时源端口 + 非零 QID 直发 host:5353，
     *  responder 单播回包。超时/无应答返回 null。 */
    suspend fun probeUnicast(serviceType: String, host: String, timeoutMs: Int): ByteArray?
    fun close()
}
