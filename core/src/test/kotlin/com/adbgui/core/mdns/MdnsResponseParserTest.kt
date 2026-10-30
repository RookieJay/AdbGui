package com.adbgui.core.mdns

import com.adbgui.core.domain.MdnsService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdnsResponseParserTest {

    private val inst = "adb-af199bb4-Q9dhj._adb-tls-connect._tcp.local"
    private val target = "Android-13.local"

    private fun phonePacket(port: Int = 37751) = DnsPacketBuilder.response(
        listOf(
            DnsPacketBuilder.Ptr("_adb-tls-connect._tcp.local", inst),
            DnsPacketBuilder.Srv(inst, port, target),
            DnsPacketBuilder.Txt(inst, mapOf("ls" to "1")),
            DnsPacketBuilder.A(target, "10.0.5.221"),
        )
    )

    @Test
    fun parses_srv_port_a_host_and_txt() {
        val services = MdnsResponseParser.parse(phonePacket())
        assertEquals(1, services.size)
        val s = services[0]
        assertEquals("adb-af199bb4-Q9dhj", s.instanceName)
        assertEquals("_adb-tls-connect._tcp", s.serviceType)
        assertEquals("10.0.5.221", s.host)
        assertEquals(37751, s.port)
        assertEquals(mapOf("ls" to "1"), s.txt)
    }

    @Test
    fun deviceSerial_extracts_serial_from_standard_instance_name() {
        assertEquals("af199bb4", MdnsService("adb-af199bb4-Q9dhj", "_adb-tls-connect._tcp", "h", 1, emptyMap()).deviceSerial)
        assertNull(MdnsService("adb-unidentified", "_adb._tcp", "h", 1, emptyMap()).deviceSerial) // 单 '-'，无 serial
    }

    @Test
    fun packet_without_srv_is_skipped() {
        val pkt = DnsPacketBuilder.response(listOf(DnsPacketBuilder.Ptr("_adb._tcp.local", "adb-unidentified._adb._tcp.local")))
        assertTrue(MdnsResponseParser.parse(pkt).isEmpty())
    }

    @Test
    fun queries_and_malformed_input_return_empty() {
        assertTrue(MdnsResponseParser.parse(ByteArray(0)).isEmpty())
        assertTrue(MdnsResponseParser.parse(ByteArray(20)).isEmpty()) // 非法长度
        // Query packet (QR=0): parser rejects non-response
        val query = byteArrayOf(
            0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            4, '_'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 'b'.code.toByte(), 0,
            0, 12, 0, 1,
        )
        assertTrue(MdnsResponseParser.parse(query).isEmpty())
    }

    @Test
    fun truncated_response_with_answer_count_but_no_record_bytes_returns_empty() {
        // 12-byte header: QR=1, AN=1, but zero bytes after the header.
        // Previously crashed with ArrayIndexOutOfBoundsException on u16(packet, 12)
        // when the parser tried to read the first answer record's type.
        val pkt = byteArrayOf(
            0, 0,                 // id
            0x84.toByte(), 0,     // flags: QR=1 AA=1
            0, 0,                 // QD=0
            0, 1,                 // AN=1  ← claims one answer
            0, 0,                 // NS=0
            0, 0,                 // AR=0
            // ← no record bytes at all
        )
        assertTrue(MdnsResponseParser.parse(pkt).isEmpty(),
            "truncated response with dangling AN=1 must not throw; contract = emptyList()")
    }

    @Test
    fun answer_record_with_rdlen_exceeding_packet_returns_empty_or_partial() {
        // Build a packet whose only answer record claims rdlen=200 but the packet
        // ends shortly after the rdlength field. Parser must not throw.
        val pkt = byteArrayOf(
            0, 0,                 // id
            0x84.toByte(), 0,     // flags: QR=1 AA=1
            0, 0,                 // QD=0
            0, 1,                 // AN=1
            0, 0, 0, 0,           // NS=0, AR=0
            // answer record:
            4, '_'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 'b'.code.toByte(), 0,  // name "_adb"
            0, 1,                 // type A
            0, 1,                 // class IN
            0, 0, 0, 120,         // ttl
            0.toByte(), 200.toByte(),      // rdlen=200 — but only 4 bytes of rdata remain
            10, 0, 5, 221.toByte(),
        )
        val result = MdnsResponseParser.parse(pkt)
        // Either we get an empty list (abort on bounds violation) or the parser
        // safely clamps rdlen — but it must not throw.
        assertTrue(result.isEmpty() || result.isNotEmpty(),
            "parser must not throw on oversized rdlen (it didn't)")
    }

    @Test
    fun large_packet_over_4096_bytes_parses() {
        // Guards that the receive-buffer enlargement (4096 → 9216) in JvmMdnsSocket
        // is matched by parser's ability to handle large packets.
        val records = mutableListOf<DnsPacketBuilder.Rec>()
        (1..80).forEach { i ->
            val instFqdn = "inst-${"x".repeat(40)}-$i._adb-tls-connect._tcp.local"
            val hostFqdn = "host-$i.local"
            records += DnsPacketBuilder.Ptr("_adb-tls-connect._tcp.local", instFqdn)
            records += DnsPacketBuilder.Srv(instFqdn, port = 30000 + i, target = hostFqdn)
            records += DnsPacketBuilder.A(hostFqdn, "10.0.${i / 256}.${i % 256}")
        }
        val bigPacket = DnsPacketBuilder.response(records)
        assertTrue(bigPacket.size > 4096, "test precondition: packet must exceed 4KB (was ${bigPacket.size})")
        val services = MdnsResponseParser.parse(bigPacket)
        assertEquals(80, services.size)
    }

    @Test
    fun real_fixture_parses_structurally() {
        val bytes = java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/test/resources/fixtures/mdns/mdns_adb_unidentified.bin"))
        val services = MdnsResponseParser.parse(bytes)
        // 实测：phone 返回 2 个 _adb-tls-connect._tcp 服务实例（含一个 "(2)" 后缀的）
        assertEquals(2, services.size)
        val first = services[0]
        assertEquals("adb-af199bb4-rY5hPZ", first.instanceName)
        assertEquals("_adb-tls-connect._tcp", first.serviceType)
        assertEquals("10.0.5.221", first.host)
        assertEquals(40709, first.port)
        assertEquals(mapOf("api" to "36", "name" to "PJD110", "v" to "1"), first.txt)

        val second = services[1]
        assertEquals("adb-af199bb4-rY5hPZ (2)", second.instanceName)
        assertEquals("_adb-tls-connect._tcp", second.serviceType)
        assertEquals("10.0.5.221", second.host)
        assertEquals(44731, second.port)
        assertEquals(mapOf("api" to "36", "name" to "PJD110", "v" to "1"), second.txt)

        // deviceSerial: "adb-af199bb4-rY5hPZ" -> "af199bb4"
        assertEquals("af199bb4", first.deviceSerial)
        assertEquals("af199bb4", second.deviceSerial)
    }
}
