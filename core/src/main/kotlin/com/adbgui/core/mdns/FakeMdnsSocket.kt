package com.adbgui.core.mdns

/** 测试用：脚本化注入待收报文。 */
class FakeMdnsSocket : MdnsSocket {
    val sentQueries = mutableListOf<String>()
    private val incoming = ArrayDeque<ByteArray>()
    @Volatile var closed = false

    override suspend fun sendQuery(serviceType: String) { sentQueries += serviceType }

    override suspend fun receive(timeoutMs: Int): ByteArray? {
        if (incoming.isNotEmpty()) return incoming.removeFirst()
        return null
    }

    /** 注入一条待收报文。 */
    fun emit(packet: ByteArray) { incoming += packet }

    override fun close() { closed = true }
}
