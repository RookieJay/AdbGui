package com.adbgui.core.mdns

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** mDNS PTR 查询包构造（RFC 1035 §4.1.2 QNAME label 编码 / RFC 6762 §18.1 查询头）。
 *  回归背景：v1 曾把 serviceType 以明文 ASCII 写入 QNAME（无 label 长度前缀），第一个字节
 *  '_'（0x5F=95）被 responder 读成 95 字节 label 越界，整包被判 malformed 丢弃 → 手机永不应答。 */
class MdnsQueryPacketTest {

    /** 期望包构造：手写 label 长度 + ASCII 内容，与实现刻意异构，避免同源错误。 */
    private fun expected(vararg labels: String): ByteArray {
        val out = mutableListOf<Byte>()
        listOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0).forEach { out += it.toByte() } // header
        labels.forEach { l ->
            out += l.length.toByte()
            l.toByteArray(Charsets.US_ASCII).forEach { out += it }
        }
        out += 0.toByte()                                   // 根 label
        listOf(0, 12, 0, 1).forEach { out += it.toByte() }  // QTYPE=PTR, QCLASS=IN
        return out.toByteArray()
    }

    @Test
    fun `builds correctly labeled QNAME for adb-tls-connect`() {
        assertContentEquals(expected("_adb-tls-connect", "_tcp", "local"), MdnsQueryPacket.build("_adb-tls-connect._tcp"))
    }

    @Test
    fun `builds legacy adb query`() {
        assertContentEquals(expected("_adb", "_tcp", "local"), MdnsQueryPacket.build("_adb._tcp"))
    }

    @Test
    fun `first QNAME byte is a label length not ascii`() {
        // '_' = 0x5F = 95 会被当成 95 字节 label → 越界。正确首字节必须是 label 长度 0x10。
        val pkt = MdnsQueryPacket.build("_adb-tls-connect._tcp")
        assertTrue(pkt[12] != '_'.code.toByte(), "QNAME 首字节必须是 label 长度，不是 ASCII")
        assertTrue((pkt[12].toInt() and 0xFF) in 1..63, "label 长度必须在 1..63")
    }
}
