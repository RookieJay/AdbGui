package com.adbgui.core.mdns

import kotlinx.coroutines.delay

/** 测试用：脚本化注入待收报文。 */
class FakeMdnsSocket : MdnsSocket {
    val sentQueries = mutableListOf<String>()
    private val incoming = ArrayDeque<ByteArray>()
    @Volatile var closed = false
    private var shouldBlock = false

    override suspend fun sendQuery(serviceType: String) { sentQueries += serviceType }

    override suspend fun receive(timeoutMs: Int): ByteArray? {
        if (incoming.isNotEmpty()) return incoming.removeFirst()
        if (shouldBlock) delay(timeoutMs.toLong())
        return null
    }

    /** 注入一条待收报文。 */
    fun emit(packet: ByteArray) { incoming += packet }

    /** 让下一次无 packet 时的 receive 阻塞完整 timeoutMs（模拟真实 socket 超时）。 */
    fun receiveWillBlock() { shouldBlock = true }

    override fun close() { closed = true }
}
