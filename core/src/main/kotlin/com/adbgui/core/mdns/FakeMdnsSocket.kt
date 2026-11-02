package com.adbgui.core.mdns

/** 测试用：脚本化注入待收报文与单播应答。 */
class FakeMdnsSocket : MdnsSocket {
    val sentQueries = mutableListOf<String>()
    /** (serviceType, host) 记录，供断言探测目标。 */
    val unicastProbes = mutableListOf<Pair<String, String>>()
    /** host -> 应答报文；未脚本化 = 超时（返回 null）。 */
    val unicastResponses = mutableMapOf<String, ByteArray>()
    private val incoming = ArrayDeque<ByteArray>()
    @Volatile var closed = false

    override suspend fun sendQuery(serviceType: String) { sentQueries += serviceType }

    override suspend fun receive(timeoutMs: Int): ByteArray? {
        if (incoming.isNotEmpty()) return incoming.removeFirst()
        return null
    }

    override suspend fun probeUnicast(serviceType: String, host: String, timeoutMs: Int): ByteArray? {
        unicastProbes += serviceType to host
        return unicastResponses[host]
    }

    /** 注入一条待收报文。 */
    fun emit(packet: ByteArray) { incoming += packet }

    override fun close() { closed = true }
}
