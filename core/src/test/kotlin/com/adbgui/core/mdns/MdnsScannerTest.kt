package com.adbgui.core.mdns

import com.adbgui.core.log.InMemoryLogger
import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MdnsScannerTest {
    private fun debugLogger() = InMemoryLogger(LogLevel.DEBUG) { System.currentTimeMillis() }

    private val phonePacket = DnsPacketBuilder.response(
        listOf(
            DnsPacketBuilder.Ptr("_adb-tls-connect._tcp.local", "adb-af199bb4-Q9dhj._adb-tls-connect._tcp.local"),
            DnsPacketBuilder.Srv("adb-af199bb4-Q9dhj._adb-tls-connect._tcp.local", 37751, "Android-13.local"),
            DnsPacketBuilder.A("Android-13.local", "10.0.5.221"),
        )
    )

    @Test
    fun emits_discovered_service_and_queries_both_types() = runTest {
        val socket = FakeMdnsSocket()
        val logger = debugLogger()
        val scanner = MdnsScanner(socket, logger, backgroundScope, Dispatchers.Unconfined, clock = { testScheduler.currentTime })
        scanner.start()
        advanceTimeBy(1_000)                                  // 触发首轮查询
        assertEquals(listOf("_adb-tls-connect._tcp", "_adb._tcp"), socket.sentQueries.sorted())
        socket.emit(phonePacket)
        advanceTimeBy(1_000)
        assertEquals(1, scanner.services.value.size)
        assertEquals("10.0.5.221", scanner.services.value[0].host)
        scanner.stop()
    }

    @Test
    fun service_expires_after_30s_without_refresh() = runTest {
        val socket = FakeMdnsSocket()
        val logger = debugLogger()
        val scanner = MdnsScanner(socket, logger, backgroundScope, Dispatchers.Unconfined, clock = { testScheduler.currentTime })
        scanner.start()
        advanceTimeBy(1_000)
        socket.emit(phonePacket)
        advanceTimeBy(1_000)
        assertEquals(1, scanner.services.value.size)
        advanceTimeBy(31_000)                                 // 超过 30s 无刷新
        assertEquals(0, scanner.services.value.size)
        scanner.stop()
    }

    @Test
    fun start_is_idempotent_and_stop_stops() = runTest {
        val socket = FakeMdnsSocket()
        val logger = debugLogger()
        val scanner = MdnsScanner(socket, logger, backgroundScope, Dispatchers.Unconfined, clock = { testScheduler.currentTime })
        scanner.start(); scanner.start()
        advanceTimeBy(11_000)                                 // 10s 周期查询两次
        assertEquals(4, socket.sentQueries.size)              // 2 types × 2 rounds
        scanner.stop(); scanner.stop()
        val before = socket.sentQueries.size
        advanceTimeBy(21_000)
        assertEquals(before, socket.sentQueries.size)
    }

    @Test
    fun unicast_probes_known_hosts_and_merges_services() = runTest {
        // 跨子网场景（2026-11-02 实测）：组播不过路由，唯一可达通道是 legacy unicast 查询。
        val socket = FakeMdnsSocket()
        socket.unicastResponses["10.0.5.221"] = phonePacket
        val scanner = MdnsScanner(
            socket, debugLogger(), backgroundScope, Dispatchers.Unconfined,
            { testScheduler.currentTime },
            knownHosts = { setOf("10.0.5.221") },
        )
        scanner.start()
        advanceTimeBy(2_000)                                  // t=0 探测更新 seen；t=1s runLoop 发布
        assertEquals(listOf("_adb-tls-connect._tcp" to "10.0.5.221"), socket.unicastProbes)
        assertEquals(1, scanner.services.value.size)
        assertEquals("10.0.5.221", scanner.services.value[0].host)
        scanner.stop()
    }

    @Test
    fun unicast_probe_timeout_is_not_an_error() = runTest {
        val socket = FakeMdnsSocket()                          // 无脚本应答 = 超时
        val scanner = MdnsScanner(
            socket, debugLogger(), backgroundScope, Dispatchers.Unconfined,
            { testScheduler.currentTime },
            knownHosts = { setOf("10.0.1.1") },
        )
        scanner.start()
        advanceTimeBy(1_000)
        assertEquals(0, scanner.services.value.size)           // 超时不产出服务，scanner 不死
        assertEquals(listOf("_adb-tls-connect._tcp" to "10.0.1.1"), socket.unicastProbes)
        scanner.stop()
    }

    @Test
    fun malformed_packet_is_logged_and_skipped() = runTest {
        val socket = FakeMdnsSocket()
        val logger = debugLogger()
        val scanner = MdnsScanner(socket, logger, backgroundScope, Dispatchers.Unconfined, clock = { testScheduler.currentTime })
        scanner.start()
        advanceTimeBy(1_000)
        // ≥12 bytes, byte[2]=0 → parser treats as non-response → empty result → triggers DEBUG log
        socket.emit(ByteArray(20))
        socket.emit(phonePacket)
        advanceTimeBy(1_000)
        assertEquals(1, scanner.services.value.size)
        assertTrue(
            logger.entries.any { it.level == LogLevel.DEBUG && it.message.contains("unparseable") },
            "expected a DEBUG log containing 'unparseable'; got: ${logger.entries.map { it.message }}"
        )
        scanner.stop()
    }
}
