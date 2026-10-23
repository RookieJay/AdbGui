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
