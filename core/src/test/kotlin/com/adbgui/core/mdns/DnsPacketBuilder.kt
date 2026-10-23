package com.adbgui.core.mdns

/** Test-only builder for DNS-SD response packets. Repeated names are encoded as
 *  compression pointers (0xC0) so the parser's pointer path gets coverage. */
object DnsPacketBuilder {
    sealed class Rec
    data class Ptr(val name: String, val target: String) : Rec()
    data class Srv(val name: String, val port: Int, val target: String) : Rec()
    data class Txt(val name: String, val pairs: Map<String, String>) : Rec()
    data class A(val name: String, val address: String) : Rec()

    fun response(records: List<Rec>): ByteArray {
        val nameOffsets = mutableMapOf<String, Int>()
        val w = ByteWriter()
        // Header: id=0, flags=0x8400 (QR=1 AA=1), QD=0, AN=records.size, NS=0, AR=0
        w.u16(0); w.u16(0x8400); w.u16(0); w.u16(records.size); w.u16(0); w.u16(0)
        for (rec in records) {
            when (rec) {
                is Ptr -> {
                    w.name(rec.name, nameOffsets)
                    w.u16(12); w.u16(1); w.u32(120)         // type PTR, class IN, ttl 120
                    val lenPos = w.size; w.u16(0)           // placeholder rdlength
                    val rdStart = w.size
                    w.name(rec.target, nameOffsets)
                    w.patchU16(lenPos, w.size - rdStart)
                }
                is Srv -> {
                    w.name(rec.name, nameOffsets)
                    w.u16(33); w.u16(1); w.u32(120)         // type SRV, class IN, ttl 120
                    val lenPos = w.size; w.u16(0)
                    val rdStart = w.size
                    w.u16(0); w.u16(0)                      // priority, weight
                    w.u16(rec.port)
                    w.name(rec.target, nameOffsets)
                    w.patchU16(lenPos, w.size - rdStart)
                }
                is Txt -> {
                    w.name(rec.name, nameOffsets)
                    w.u16(16); w.u16(1); w.u32(120)         // type TXT, class IN, ttl 120
                    val lenPos = w.size; w.u16(0)
                    val rdStart = w.size
                    rec.pairs.forEach { (k, v) ->
                        val entry = "$k=$v".toByteArray(Charsets.US_ASCII)
                        w.u8(entry.size); w.bytes(entry)
                    }
                    w.patchU16(lenPos, w.size - rdStart)
                }
                is A -> {
                    w.name(rec.name, nameOffsets)
                    w.u16(1); w.u16(1); w.u32(120)          // type A, class IN, ttl 120
                    w.u16(4)
                    rec.address.split('.').forEach { w.u8(it.toInt()) }
                }
            }
        }
        return w.toByteArray()
    }

    private class ByteWriter {
        private val buf = mutableListOf<Byte>()
        val size get() = buf.size
        fun u8(v: Int) { buf += v.toByte() }
        fun u16(v: Int) { u8(v shr 8); u8(v and 0xFF) }
        fun u32(v: Int) { u16(v shr 16); u16(v and 0xFFFF) }
        fun bytes(w: ByteWriter) { w.buf.forEach { buf += it } }
        fun bytes(b: ByteArray) { b.forEach { buf += it } }
        fun patchU16(pos: Int, v: Int) { buf[pos] = (v shr 8).toByte(); buf[pos + 1] = v.toByte() }
        fun name(n: String, offsets: MutableMap<String, Int>) {
            if (offsets.containsKey(n)) { u16(0xC000 or offsets[n]!!); return }
            offsets[n] = buf.size
            n.split('.').forEach { label ->
                u8(label.length)
                label.toByteArray(Charsets.US_ASCII).forEach { buf += it }
            }
            u8(0)
        }
        fun toByteArray() = buf.toByteArray()
    }
}
