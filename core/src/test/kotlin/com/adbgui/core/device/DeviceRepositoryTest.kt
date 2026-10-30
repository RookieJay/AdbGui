package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.log.InMemoryLogger
import com.adbgui.core.log.LogLevel
import com.adbgui.core.log.NoopLogger
import com.adbgui.core.mdns.DnsPacketBuilder
import com.adbgui.core.mdns.FakeMdnsSocket
import com.adbgui.core.mdns.MdnsScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryTest {
    @Test
    fun merges_live_and_history() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE)))
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("rep"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("xyz", DeviceType.WIRELESS, "10.0.0.1", 5555) // offline historical
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val list = repo.devices.value
        assertEquals(2, list.size)
        assertTrue(list.any { it.serial == "abc" && it.isLive })
        assertTrue(list.any { it.serial == "xyz" && !it.isLive })
        repo.stop()
    }

    @Test
    fun connectWireless_persists_history_on_success() = runTest {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("rep2"), clock = { 42L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 192.168.1.50:5555", ""))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val r = repo.connectWireless("192.168.1.50", 5555)
        assertTrue(r.success)
        val h = history.load().first()
        assertEquals("192.168.1.50", h.wirelessIp)
        assertEquals(5555, h.wirelessPort)
        repo.stop()
    }

    @Test
    fun connectWireless_auto_sets_alias_from_brand_model_when_no_alias() = runTest {
        // On a successful connect, the repo should fetch getprop and set the alias to
        // "<brand> <model>" (e.g. "OnePlus PJZ110") so the device list shows a friendly name
        // instead of a bare serial. Only when the device has NO existing alias — never
        // overwrite a user-set name.
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("alias"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 10.0.5.221:43849", ""))
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0,
            "[ro.product.brand]: [OnePlus]\n[ro.product.manufacturer]: [OnePlus]\n[ro.product.model]: [PJZ110]\n",
            ""))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        repo.connectWireless("10.0.5.221", 43849)
        // Alias-setting is async (launch) — drain.
        val deadline = System.currentTimeMillis() + 3_000
        var h = history.load().firstOrNull()
        while (h?.alias == null && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            h = history.load().firstOrNull()
            if (h?.alias != null) break
            Thread.sleep(30)
        }
        assertEquals("OnePlus PJZ110", h?.alias)
        repo.stop()
    }

    @Test
    fun connectWireless_does_not_overwrite_existing_alias() = runTest {
        // If the user already named the device (or a previous auto-name set it), a new
        // connect must NOT clobber it.
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("alias2"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        // Pre-seed an alias via a first connect, then change it manually, then reconnect.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 10.0.5.221:43849", ""))
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0,
            "[ro.product.brand]: [OnePlus]\n[ro.product.manufacturer]: [OnePlus]\n[ro.product.model]: [PJZ110]\n",
            ""))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        repo.connectWireless("10.0.5.221", 43849)
        // Wait for auto-alias, then user renames.
        val deadline1 = System.currentTimeMillis() + 3_000
        while (history.load().firstOrNull()?.alias == null && System.currentTimeMillis() < deadline1) {
            advanceUntilIdle(); Thread.sleep(30)
        }
        repo.setAlias("10.0.5.221:43849", "我的手机")
        // Reconnect — must not overwrite "我的手机".
        repo.connectWireless("10.0.5.221", 43849)
        val deadline2 = System.currentTimeMillis() + 3_000
        var h = history.load().firstOrNull()
        while (System.currentTimeMillis() < deadline2) {
            advanceUntilIdle()
            h = history.load().firstOrNull()
            if (h?.alias == "我的手机") break
            Thread.sleep(30)
        }
        assertEquals("我的手机", h?.alias)
        repo.stop()
    }

    private fun getpropResult(brand: String, model: String) = AdbProcessResult(0,
        "[ro.product.brand]: [$brand]\n[ro.product.manufacturer]: [$brand]\n[ro.product.model]: [$model]\n", "")

    /** Poll-until with wall-clock deadline. Pumps runCurrent (NOT advanceUntilIdle — that
     *  would fast-forward virtual time and burn through self-scheduled 60s retry chains);
     *  tests advance time explicitly via advanceTimeBy when they want retries to fire. */
    private suspend fun TestScope.waitUntil(timeoutMs: Long = 3_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            runCurrent()
            if (condition()) break
            Thread.sleep(30)
        }
    }

    @Test
    fun online_usb_device_without_alias_gets_auto_named() = runTest {
        // USB devices never go through connectWireless — auto-name must trigger when the
        // tracker reports the device ONLINE. Covers USB plug-in and "app started while a
        // device was already connected".
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE)))
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("usbname"), clock = { 0L }, io = Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("getprop"), getpropResult("Xiaomi", "MiTV-MFTR0"))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        waitUntil { history.load().firstOrNull()?.alias != null }
        assertEquals("Xiaomi MiTV-MFTR0", history.load().first { it.serial == "abc" }.alias)
        repo.stop()
    }

    @Test
    fun offline_device_is_not_auto_named() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("abc", DeviceStatus.OFFLINE)))
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("offname"), clock = { 0L }, io = Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("getprop"), getpropResult("Xiaomi", "MiTV-MFTR0"))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        advanceUntilIdle()
        assertTrue(runner.runs.none { args -> args.any { it.contains("getprop") } })
        repo.stop()
    }

    @Test
    fun auto_name_retries_after_transient_failure() = runTest {
        // The 2026-09-26 bug: getprop right after connect failed transiently, runCatching
        // swallowed it with no log — the device stayed unnamed forever. Worse, the device
        // list then stays unchanged, and StateFlow conflates equal values, so recompute
        // never fires again either. Retries must be self-scheduled: a failed attempt
        // re-arms itself after a delay (while the device stays online).
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE)))
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("retry"), clock = { 0L }, io = Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner() // no getprop script → default exit 1: first attempt fails
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        // 1st attempt fails (device reported online by the initial recompute).
        waitUntil { runner.runs.any { args -> args.any { it.contains("getprop") } } }
        runCurrent()
        assertEquals(null, history.load().firstOrNull()?.alias)
        assertEquals(1, runner.runs.count { args -> args.any { it.contains("getprop") } })
        // getprop now succeeds → the self-scheduled retry (virtual time) names the device.
        runner.whenArgsContains(listOf("getprop"), getpropResult("Xiaomi", "MiTV-MFTR0"))
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals("Xiaomi MiTV-MFTR0", history.load().first { it.serial == "abc" }.alias)
        repo.stop()
    }

    @Test
    fun auto_name_failure_is_logged_at_warn() = runTest {
        // The swallowed failure left no trace in the logs — undiagnosable. Failures must log.
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE)))
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("logname"), clock = { 0L }, io = Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner() // default exit 1
        val log = InMemoryLogger(LogLevel.DEBUG, clock = { 0L })
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, log, this)
        waitUntil { log.entries.any { it.level == LogLevel.WARN && it.message.contains("abc") } }
        assertTrue(log.entries.any { it.level == LogLevel.WARN && it.message.contains("abc") },
            "auto-name failure must be logged at WARN (was swallowed silently before)")
        repo.stop()
    }

    @Test
    fun recompute_maps_lastUsedAt_and_tag_from_history() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE)))
        }
        val dir = Files.createTempDirectory("rep3")
        val history = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("abc", DeviceType.USB, null, null)
        history.touchLastUsed("abc")
        history.setTag("abc", "lab")
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val v = repo.devices.value.first { it.serial == "abc" }
        assertEquals("lab", v.tag)
        assertEquals(0L, v.lastUsedAt) // history store clock=0 → touchLastUsed stamped 0
        repo.stop()
    }

    @Test
    fun touchLastUsed_updates_history_and_view() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE)))
        }
        val dir = Files.createTempDirectory("rep4")
        // History store owns the timestamp clock; touchLastUsed stamps with THIS clock.
        val history = DeviceHistoryStore(dir, clock = { 4242L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("abc", DeviceType.USB, null, null)
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        repo.touchLastUsed("abc")
        val v = repo.devices.value.first { it.serial == "abc" }
        assertEquals(4242L, v.lastUsedAt)
        // Persisted too:
        val h = history.load().first { it.serial == "abc" }
        assertEquals(4242L, h.lastUsedAt)
        repo.stop()
    }

    @Test
    fun setTag_updates_history_and_view() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE)))
        }
        val dir = Files.createTempDirectory("rep5")
        val history = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("abc", DeviceType.USB, null, null)
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        repo.setTag("abc", "lab")
        val v = repo.devices.value.first { it.serial == "abc" }
        assertEquals("lab", v.tag)
        repo.stop()
    }

    @Test
    fun clearTag_clears_tag_from_all_devices_in_one_write() = runTest {
        // Three devices tagged "lab"; clearTag must untag all three atomically. (Per-device
        // setTag(null) races and only clears one — the bug this guards against.)
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(
                DeviceSnapshot("a", DeviceStatus.ONLINE),
                DeviceSnapshot("b", DeviceStatus.ONLINE),
                DeviceSnapshot("c", DeviceStatus.ONLINE),
            ))
        }
        val dir = Files.createTempDirectory("rep6")
        val history = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("a", DeviceType.USB, null, null); history.setTag("a", "lab")
        history.upsert("b", DeviceType.USB, null, null); history.setTag("b", "lab")
        history.upsert("c", DeviceType.USB, null, null); history.setTag("c", "lab")
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        repo.clearTag("lab")
        val views = repo.devices.value.associateBy { it.serial }
        assertEquals(null, views["a"]?.tag)
        assertEquals(null, views["b"]?.tag)
        assertEquals(null, views["c"]?.tag)
        // Persisted too:
        val hist = history.load().associateBy { it.serial }
        assertEquals(null, hist["a"]?.tag)
        assertEquals(null, hist["b"]?.tag)
        assertEquals(null, hist["c"]?.tag)
        repo.stop()
    }

    @Test
    fun connectWireless_learns_deviceId_and_merges_stale_same_ip_entries() = runTest {
        // After a successful wireless connect, the repo must query `getprop ro.serialno`
        // to learn the stable deviceId, then call history.mergeOnConnect so stale same-IP
        // entries (no deviceId, different port) collapse into one.
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("rep-learn"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        // Pre-seed stale same-IP entry (no deviceId, older port)
        history.upsert("10.0.5.221:38897", DeviceType.WIRELESS, "10.0.5.221", 38897)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 10.0.5.221:37751", ""))
        // pty adds \r; sanitize strips it, then implementation must .trim() the trailing \n.
        runner.whenArgsContains(listOf("getprop", "ro.serialno"), AdbProcessResult(0, "af199bb4\r\n", ""))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val r = repo.connectWireless("10.0.5.221", 37751)
        assertTrue(r.success)
        val entries = history.load()
        assertEquals(1, entries.size, "stale same-IP entry must be merged away")
        val h = entries.first()
        assertEquals("10.0.5.221:37751", h.serial)
        assertEquals("af199bb4", h.deviceId)
        repo.stop()
    }

    @Test
    fun connectWireless_serialno_failure_degrades_to_plain_upsert() = runTest {
        // If `getprop ro.serialno` fails (unauthorized / offline race), the connect must
        // still succeed — history is written via plain upsert (no merge), deviceId stays null.
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("rep-degrade"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 10.0.5.221:37751", ""))
        runner.whenArgsContains(listOf("getprop", "ro.serialno"), AdbProcessResult(1, "", "error"))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val r = repo.connectWireless("10.0.5.221", 37751)
        assertTrue(r.success)
        val entries = history.load()
        assertEquals(1, entries.size)
        val h = entries.first()
        assertEquals("10.0.5.221:37751", h.serial)
        assertEquals(null, h.deviceId, "getprop failure must not block the connect — deviceId stays null")
        repo.stop()
    }

    @Test
    fun renameTag_renames_tag_on_all_devices_and_merges_into_existing() = runTest {
        // "a" and "b" wear "lab"; "c" already wears "desk". Renaming lab→desk must move both
        // into "desk" in one write — the per-device setTag race clearTag guards against applies
        // here too.
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(
                DeviceSnapshot("a", DeviceStatus.ONLINE),
                DeviceSnapshot("b", DeviceStatus.ONLINE),
                DeviceSnapshot("c", DeviceStatus.ONLINE),
            ))
        }
        val dir = Files.createTempDirectory("rep7")
        val history = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("a", DeviceType.USB, null, null); history.setTag("a", "lab")
        history.upsert("b", DeviceType.USB, null, null); history.setTag("b", "lab")
        history.upsert("c", DeviceType.USB, null, null); history.setTag("c", "desk")
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        repo.renameTag("lab", "desk")
        // View reflects the merge immediately:
        val views = repo.devices.value.associateBy { it.serial }
        assertEquals("desk", views["a"]?.tag)
        assertEquals("desk", views["b"]?.tag)
        assertEquals("desk", views["c"]?.tag)
        // Persisted too:
        val hist = history.load().associateBy { it.serial }
        assertEquals("desk", hist["a"]?.tag)
        assertEquals("desk", hist["b"]?.tag)
        repo.stop()
    }

    // Helper: build a packet advertising a single TLS service for the given serial/ip/port.
    private fun tlsServicePacket(instanceName: String, port: Int, host: String) =
        DnsPacketBuilder.response(
            listOf(
                DnsPacketBuilder.Ptr("_adb-tls-connect._tcp.local", "$instanceName._adb-tls-connect._tcp.local"),
                DnsPacketBuilder.Srv("$instanceName._adb-tls-connect._tcp.local", port, "android.local"),
                DnsPacketBuilder.A("android.local", host),
            )
        )

    /** Drive a real [MdnsScanner] on a [FakeMdnsSocket] — zero new fakes (brief's decision). */
    private fun TestScope.startScannerOn(socket: FakeMdnsSocket): MdnsScanner {
        val scanner = MdnsScanner(socket, NoopLogger, backgroundScope, Dispatchers.Unconfined) { testScheduler.currentTime }
        scanner.start()
        // First loop iteration: send queries + block on receive. The scanner is now live.
        advanceTimeBy(1_000)
        return scanner
    }

    /** Emit [packet] and pump the scanner long enough for the service to land in services. */
    private fun TestScope.publishPacket(socket: FakeMdnsSocket, packet: ByteArray) {
        socket.emit(packet)
        advanceTimeBy(1_000)
    }

    @Test
    fun mdns_service_matching_known_deviceId_triggers_connect() = runTest {
        // History holds a device keyed by deviceId (not the current port). mDNS discovers a
        // TLS service whose instanceName embeds the SAME deviceId — repo must auto-connect
        // to the NEW port and merge the stale history entry.
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("mdns-known"), clock = { 0L }, io = Dispatchers.Unconfined)
        // Pre-seed via mergeOnConnect so deviceId is populated (plain upsert cannot set it).
        history.mergeOnConnect("af199bb4", "10.0.5.221:38897", DeviceType.WIRELESS, "10.0.5.221", 38897)

        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 10.0.5.221:37751", ""))
        runner.whenArgsContains(listOf("getprop", "ro.serialno"), AdbProcessResult(0, "af199bb4\r\n", ""))
        runner.whenArgsContains(listOf("getprop"), getpropResult("OnePlus", "PJZ110"))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})

        val socket = FakeMdnsSocket()
        val scanner = startScannerOn(socket)
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, mdns = scanner)

        publishPacket(socket, tlsServicePacket("adb-af199bb4-Q9dhj", 37751, "10.0.5.221"))
        // Pump repo's collector + the launched connectWireless coroutine.
        advanceUntilIdle()

        val connectCalls = runner.runs.filter { args -> args.any { it == "connect" } }
        assertTrue(connectCalls.isNotEmpty(), "mDNS match on known deviceId must trigger adb connect")
        val entries = history.load()
        assertEquals(1, entries.size, "stale same-deviceId entry must be merged")
        assertEquals("10.0.5.221:37751", entries.first().serial)
        assertEquals("af199bb4", entries.first().deviceId)
        repo.stop(); scanner.stop()
    }

    @Test
    fun mdns_service_unknown_goes_to_discovered_only() = runTest {
        // No history — mDNS finds a service that matches nothing. It must show up in
        // [discovered] but NOT trigger any adb connect.
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("mdns-unknown"), clock = { 0L }, io = Dispatchers.Unconfined)

        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})

        val socket = FakeMdnsSocket()
        val scanner = startScannerOn(socket)
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, mdns = scanner)

        publishPacket(socket, tlsServicePacket("adb-mysterydevice-XyZ123", 42069, "192.168.1.77"))
        advanceUntilIdle()

        assertEquals(1, repo.discovered.value.size)
        assertEquals("192.168.1.77", repo.discovered.value[0].host)
        assertEquals(42069, repo.discovered.value[0].port)
        assertTrue(runner.runs.none { args -> args.any { it == "connect" } },
            "unknown mDNS service must NOT trigger adb connect")
        repo.stop(); scanner.stop()
    }

    @Test
    fun unknown_wireless_serial_coming_online_gets_deviceId_learned() = runTest {
        // A wireless device that adb server auto-connected shows up in tracker as ONLINE
        // with its ip:port serial. History has an entry but no deviceId yet. autoName
        // runs (sets alias) AND the new lazy-learning code path must also query getprop
        // ro.serialno and merge the deviceId into history.
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(DeviceSnapshot("10.0.5.221:37751", DeviceStatus.ONLINE)))
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("mdns-learn"), clock = { 0L }, io = Dispatchers.Unconfined)
        // Pre-seed wireless entry WITHOUT deviceId (e.g. learned from an earlier connect
        // where getprop ro.serialno failed — see the degrade test).
        history.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)
        assertNull(history.load().first().deviceId, "fixture: entry starts without deviceId")

        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("getprop", "ro.serialno"), AdbProcessResult(0, "af199bb4\r\n", ""))
        runner.whenArgsContains(listOf("getprop"), getpropResult("OnePlus", "PJZ110"))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)

        waitUntil { history.load().firstOrNull { it.serial == "10.0.5.221:37751" }?.deviceId != null }
        val entry = history.load().first { it.serial == "10.0.5.221:37751" }
        assertEquals("af199bb4", entry.deviceId)
        repo.stop()
    }
}
