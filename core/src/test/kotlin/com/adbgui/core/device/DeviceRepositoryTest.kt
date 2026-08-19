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
}
