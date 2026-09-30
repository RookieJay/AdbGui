package com.adbgui.core.device

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.log.NoopLogger
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogcatRecordingManagerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private class FakeTracker : IDeviceTracker {
        override val devices = MutableStateFlow<List<DeviceSnapshot>>(emptyList())
    }

    private val line1 = "08-17 10:23:45.100  100  200 I Tag1: hello"

    private fun manager(
        runner: FakeAdbProcessRunner, scope: kotlinx.coroutines.CoroutineScope, dir: java.nio.file.Path,
        tracker: FakeTracker = FakeTracker(),
    ): LogcatRecordingManager {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        return LogcatRecordingManager(cmd, tracker, settings, NoopLogger, scope, dir, io = Dispatchers.Unconfined)
    }

    @Test fun manual_record_lifecycle_and_filename_sanitization() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("192.168.1.5:5555", DeviceStatus.ONLINE))
        assertEquals(ManualRecordState.Idle, m.manualState.value)
        m.startManual("192.168.1.5:5555")     // 无线 serial 含 ':'
        advanceUntilIdle()
        val rec = m.manualState.value
        assertTrue(rec is ManualRecordState.Recording)
        assertEquals("192.168.1.5:5555", rec.serial)
        assertTrue(rec.file.fileName.toString().startsWith("manual_192.168.1.5_5555_"))
        assertTrue(rec.file.fileName.toString().endsWith(".log"))
        m.stopManual("user")
        advanceUntilIdle()
        val stopped = m.manualState.value
        assertTrue(stopped is ManualRecordState.Stopped)
        assertEquals("user", stopped.reason)
        assertTrue(Files.exists(stopped.file))
        assertTrue(Files.readString(stopped.file).contains(line1))
        m.stop()
        advanceUntilIdle()
    }

    @Test fun startManual_while_recording_is_ignored() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        m.startManual("abc")
        advanceUntilIdle()
        val first = (m.manualState.value as ManualRecordState.Recording).serial
        m.startManual("other")                // 一次最多一个会话（spec §3.2）
        assertEquals(first, (m.manualState.value as ManualRecordState.Recording).serial)
        m.stopManual("user")
        m.stop()
        advanceUntilIdle()
    }

    @Test fun stop_all_stops_manual() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        m.startManual("abc")
        advanceUntilIdle()
        m.stop()
        advanceUntilIdle()
        assertTrue(m.manualState.value is ManualRecordState.Stopped)
    }

    // --- auto recording ---

    @Test fun auto_record_starts_and_stops_with_device_online_state() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        assertTrue(m.autoStates.value.containsKey("abc"))
        tracker.devices.value = emptyList()   // 设备下线 → stop recorder → flush file
        advanceUntilIdle()
        assertTrue(!m.autoStates.value.containsKey("abc"))
        val auto = dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }
        assertEquals(1, auto?.size)
        assertTrue(Files.readString(auto[0].toPath()).contains(line1))
        m.stop()
        advanceUntilIdle()
    }

    @Test fun auto_record_disabled_setting_starts_nothing() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        settings.update { it.copy(logcatAutoRecord = false) }
        val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir, io = Dispatchers.Unconfined)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        assertEquals(0, dir.toFile().listFiles { f -> f.name.startsWith("auto_") }?.size)
        // 运行时打开开关 → 立即补启（spec §3.2）
        settings.update { it.copy(logcatAutoRecord = true) }
        advanceUntilIdle()
        assertEquals(1, dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }?.size)
        m.stop()
        advanceUntilIdle()
    }

    @Test fun manual_recording_stops_when_device_disconnects() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        m.startManual("abc")
        advanceUntilIdle()
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.OFFLINE))
        advanceUntilIdle()
        val st = m.manualState.value
        assertTrue(st is ManualRecordState.Stopped)
        assertEquals("device_disconnected", st.reason)
        m.stop()
        advanceUntilIdle()
    }

    @Test fun rotated_auto_recorder_is_replaced_with_a_new_file() = runTest {
        val runner = FakeAdbProcessRunner()
        // once：第一个 recorder 收 2 行后 EOF；后续 recorder 拿到空流（streamLines 默认空）不轮转 → 稳定
        runner.setStreamLinesOnce(listOf(line1, line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        // line1 ≈ 40 chars：阈值取 1 行字节数 → 写完第 1 行即轮转
        val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir,
            io = Dispatchers.Unconfined, rotationBytes = line1.length + 1L)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        // recorder1 轮转收尾（1 行），manager 续开 recorder2（空流、存活）→ auto_ 文件共 2 个
        val autoFiles = dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }
        assertEquals(2, autoFiles?.size)
        val lineCounts = autoFiles!!.map { Files.readString(it.toPath()).lines().count { l -> l.isNotBlank() } }
        assertEquals(1, lineCounts.count { it == 1 })
        m.stop()
        advanceUntilIdle()
    }

    // --- startup cleanup ---

    @Test fun startup_cleanup_deletes_old_auto_but_keeps_manual() = runTest {
        val runner = FakeAdbProcessRunner()
        val dir = Files.createTempDirectory("adbgui-rec")
        val oldAuto = dir.resolve("auto_old.log"); oldAuto.writeText("x")
        val oldManual = dir.resolve("manual_old.log"); oldManual.writeText("x")
        val now = System.currentTimeMillis()
        oldAuto.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        oldManual.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        settings.update { it.copy(logcatAutoRecord = false) }   // 别让 auto 录制添新文件
        val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir,
            io = Dispatchers.Unconfined, clock = { now })
        advanceUntilIdle()
        assertTrue(!Files.exists(oldAuto))     // 8 天前 auto → 删
        assertTrue(Files.exists(oldManual))    // manual 永不自动删（spec §3.2）
        m.stop()
        advanceUntilIdle()
    }
}
