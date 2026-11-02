package com.adbgui.core.mdns

/** mDNS PTR 查询包构造（纯函数，RFC 1035 QNAME label 编码 + RFC 6762 §18.1 查询头）。
 *  QNAME 各 label 以单字节长度前缀编码，QTYPE=PTR、QCLASS=IN。
 *  组播查询按 mDNS 惯例 QID=0；legacy unicast 查询（§18.1）要求非零 QID。 */
object MdnsQueryPacket {
    fun build(serviceType: String, qid: Int = 0): ByteArray {
        require(qid in 0..0xFFFF) { "qid 越界: $qid" }
        val out = mutableListOf<Byte>()
        out += listOf((qid shr 8) and 0xFF, qid and 0xFF, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0).map { it.toByte() }
        "$serviceType.local".split('.').forEach { label ->
            require(label.length in 1..63) { "label 长度越界: '$label'" }
            out += label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).forEach { out += it }
        }
        out += 0.toByte()                                   // 根 label
        out += listOf(0, 12, 0, 1).map { it.toByte() }      // QTYPE=PTR, QCLASS=IN
        return out.toByteArray()
    }
}
