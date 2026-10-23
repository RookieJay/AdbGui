package com.adbgui.core.mdns

import com.adbgui.core.domain.MdnsService

/** 解析 mDNS/DNS-SD 响应报文（RFC 6762 / RFC 2782）。纯函数、无状态：malformed 输入返回 emptyList()，
 *  调用方负责 DEBUG 级记录原文（解析约定：不静默吞，由调用方兜底）。 */
object MdnsResponseParser {
    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33

    fun parse(packet: ByteArray): List<MdnsService> {
        if (packet.size < 12) return emptyList()
        if (packet[2].toInt() and 0x80 == 0) return emptyList()          // 不是 response
        val qd = u16(packet, 4)
        val an = u16(packet, 6)
        val ns = u16(packet, 8)
        val ar = u16(packet, 10)
        val r = Reader(packet)
        r.pos = 12
        repeat(qd) { r.readName(); r.skip(4) }                            // type + class

        val instances = mutableListOf<String>()        // PTR rdata: "<instance>.<type>.local"
        val srvs = mutableMapOf<String, Pair<Int, String>>()   // instanceFqdn -> (port, target)
        val txts = mutableMapOf<String, Map<String, String>>()
        val addrs = mutableMapOf<String, String>()     // name -> ipv4

        // 处理 AN + NS + AR 所有资源记录（同 wire format）；mDNS 把 SRV/TXT/A 多放在 AR
        val total = an + ns + ar
        repeat(total) {
            val name = r.readName()
            val type = u16(packet, r.pos); r.skip(2)
            r.skip(2); r.skip(4)                                          // class, ttl
            val rdlen = u16(packet, r.pos); r.pos += 2
            val rdataStart = r.pos
            when (type) {
                TYPE_PTR -> instances += r.readName()
                TYPE_SRV -> {
                    r.skip(4)                                             // priority + weight
                    val port = u16(packet, r.pos); r.skip(2)
                    srvs[name] = port to r.readName()
                }
                TYPE_TXT -> {
                    val pairs = mutableMapOf<String, String>()
                    var p = r.pos
                    val end = rdataStart + rdlen
                    while (p < end) {
                        val len = packet[p].toInt() and 0xFF
                        if (len == 0 || p + 1 + len > end) break
                        val entry = String(packet, p + 1, len, Charsets.US_ASCII)
                        val k = entry.substringBefore('=')
                        if (entry.contains('=')) pairs[k] = entry.substringAfter('=')
                        else pairs[k] = ""                                // DNS-SD flag 形式
                        p += 1 + len
                    }
                    txts[name] = pairs
                }
                TYPE_A -> if (rdlen == 4) {
                    addrs[name] = "${u8(packet, rdataStart)}.${u8(packet, rdataStart + 1)}.${u8(packet, rdataStart + 2)}.${u8(packet, rdataStart + 3)}"
                }
            }
            r.pos = rdataStart + rdlen
        }

        return instances.mapNotNull { fqdn ->
            val withoutLocal = fqdn.removeSuffix(".local")
            val inst = withoutLocal.substringBefore('.')
            val type = withoutLocal.substringAfter('.', "")
            if (type.isEmpty() || !type.startsWith('_')) return@mapNotNull null
            val srv = srvs[fqdn] ?: return@mapNotNull null                // 无 SRV = 无端口，跳过
            val host = addrs[srv.second].takeIf { !it.isNullOrEmpty() } ?: addrs[fqdn] ?: ""
            MdnsService(inst, type, host, srv.first, txts[fqdn] ?: emptyMap())
        }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** 域名读取，支持 0xC0 压缩指针与防环。读取完，pos 停在名字（含指针）之后的下一字节。 */
    private class Reader(val b: ByteArray) {
        var pos = 0
        fun readName(): String {
            val labels = mutableListOf<String>()
            var p = pos
            var next = -1
            var jumps = 0
            loop@ while (p < b.size) {
                val len = b[p].toInt() and 0xFF
                when {
                    len == 0 -> { p++; break@loop }
                    len and 0xC0 == 0xC0 -> {
                        if (p + 1 >= b.size) break@loop
                        if (next < 0) next = p + 2
                        p = ((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                        if (++jumps > 32) break@loop                        // 环保护
                    }
                    else -> {
                        if (p + 1 + len > b.size) break@loop
                        labels += String(b, p + 1, len, Charsets.US_ASCII)
                        p += 1 + len
                    }
                }
            }
            pos = if (next >= 0) next else p
            return labels.joinToString(".")
        }
        fun skip(n: Int) { pos += n }
    }
}
