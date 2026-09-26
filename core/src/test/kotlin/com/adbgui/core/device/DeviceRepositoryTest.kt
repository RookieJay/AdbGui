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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
