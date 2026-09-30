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
        val m = manager(runner, this, dir)
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
    }

    @Test fun startManual_while_recording_is_ignored() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val m = manager(runner, this, dir)
        m.startManual("abc")
        advanceUntilIdle()
        val first = (m.manualState.value as ManualRecordState.Recording).serial
        m.startManual("other")                // 一次最多一个会话（spec §3.2）
        assertEquals(first, (m.manualState.value as ManualRecordState.Recording).serial)
        m.stopManual("user")
    }

    @Test fun stop_all_stops_manual() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val m = manager(runner, this, dir)
        m.startManual("abc")
        advanceUntilIdle()
        m.stop()
        advanceUntilIdle()
        assertTrue(m.manualState.value is ManualRecordState.Stopped)
    }
}
