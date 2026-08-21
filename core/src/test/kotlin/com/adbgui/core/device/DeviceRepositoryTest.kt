package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
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
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 0L })
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
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 42L })
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
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 0L })
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
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 0L })
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
}
