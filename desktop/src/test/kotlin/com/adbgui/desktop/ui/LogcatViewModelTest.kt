package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.IDeviceTracker
import com.adbgui.core.device.LogcatController
import com.adbgui.core.device.LogcatRecordingManager
import com.adbgui.core.device.LogcatStatus
import com.adbgui.core.device.ManualRecordState
import com.adbgui.core.device.RecordState
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.log.NoopLogger
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogcatViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private class FakeTracker(
        initial: List<DeviceSnapshot> = emptyList(),
    ) : IDeviceTracker {
        override val devices = MutableStateFlow(initial)
    }

    private fun controller(
        runner: FakeAdbProcessRunner, scope: CoroutineScope,
    ): LogcatController {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
        return LogcatController(cmd, NoopLogger, scope, ringCap = 5)
    }

    private fun recordingManager(
        runner: FakeAdbProcessRunner, scope: CoroutineScope,
    ): LogcatRecordingManager {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
        val dir = Files.createTempDirectory("adbgui-rec")
        return LogcatRecordingManager(
            cmd, FakeTracker(listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))),
            SettingsStore(dir, io = Dispatchers.Unconfined), NoopLogger,
            scope, dir, io = Dispatchers.Unconfined,
        )
    }

    @Test fun selected_serial_change_starts_logcat_and_lines_flow() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>(null)
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(true), this)
        vm.onPageEntered()
        // emulate auto-select
        selected.value = "abc"
        advanceUntilIdle()
        assertEquals(1, vm.lines.value.size)
        vm.stop()
        controller.stop()
    }

    @Test fun pause_resume_clear_forward_to_controller() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow("abc")
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(true), this)
        vm.onPageEntered()
        advanceUntilIdle()
        assertEquals(com.adbgui.core.device.LogcatStatus.RUNNING, controller.status.value)
        vm.pause()
        assertEquals(com.adbgui.core.device.LogcatStatus.PAUSED, controller.status.value)
        vm.resume()
        assertEquals(com.adbgui.core.device.LogcatStatus.RUNNING, controller.status.value)
        vm.clear()
        advanceUntilIdle()
        assertEquals(0, vm.lines.value.size)
        vm.stop(); controller.stop()
    }

    @Test fun fixLogcat_runs_setprop_and_restarts_stream_clears_error() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        runner.whenArgsContains(
            listOf("shell", "setprop persist.sys.logd.level V; stop logd; start logd"),
            AdbProcessResult(0, "", ""),
        )
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow("abc")
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(true), this)
        advanceUntilIdle()
        vm.fixLogcat()
        advanceUntilIdle()
        // The fix shell command went out. runs is List<List<String>> (argv per run); check substring.
        assertTrue(runner.runs.any { args -> args.any { it.contains("setprop persist.sys.logd.level V") } })
        // No error surfaced; fixing flag cycled back to false.
        assertNull(vm.fixError.value)
        assertEquals(false, vm.fixing.value)
        vm.stop(); controller.stop()
    }

    @Test fun fixLogcat_surfaces_error_when_command_fails() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        runner.whenArgsContains(
            listOf("shell", "setprop persist.sys.logd.level V; stop logd; start logd"),
            AdbProcessResult(1, "", "setprop: permission denied"),
        )
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow("abc")
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(true), this)
        advanceUntilIdle()
        vm.fixLogcat()
        advanceUntilIdle()
        assertNotNull(vm.fixError.value)
        assertEquals(false, vm.fixing.value)
        vm.stop(); controller.stop()
    }

    @Test fun exportFull_writes_dumped_lines_to_file_and_tracks_progress() = runTest {
        // -d full dump: stream two lines via once-mode, write matching (all-level default) raw
        // lines to the file, track progress per line, clear exporting on completion.
        val runner = FakeAdbProcessRunner()
        runner.setStreamLinesOnce(listOf(
            "08-17 10:23:45.100  100  200 I Tag: one",
            "08-17 10:23:45.200  100  201 E Tag: two",
        ))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow("abc")
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(true), this)
        val tmp = Files.createTempFile("logcat-export", ".txt")
        vm.exportFull(tmp.toString())
        advanceUntilIdle()
        val content = Files.readString(tmp)
        assertTrue(content.contains("Tag: one"))
        assertTrue(content.contains("Tag: two"))
        assertEquals(2L, vm.exportProgress.value)
        assertEquals(false, vm.exporting.value)
        assertNull(vm.exportError.value)
        vm.stop(); controller.stop()
    }

    @Test fun exportFull_surfaces_error_when_no_device_selected() = runTest {
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow(null)
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(false), this)
        val tmp = Files.createTempFile("logcat-export", ".txt")
        vm.exportFull(tmp.toString())
        advanceUntilIdle()
        assertNotNull(vm.exportError.value)
        assertEquals(false, vm.exporting.value)
        vm.stop(); controller.stop()
    }

    @Test fun no_load_before_page_entered() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>("abc")
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(true), this)
        advanceUntilIdle()
        assertEquals(0, vm.lines.value.size, "must not stream before the page is entered")
        vm.stop(); controller.stop()
    }

    @Test fun page_entered_starts_stream() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>("abc")
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(true), this)
        vm.onPageEntered()
        advanceUntilIdle()
        assertEquals(1, vm.lines.value.size)
        vm.stop(); controller.stop()
    }

    @Test fun serial_becomes_null_stops_stream() = runTest {
        // 修 CHANGELOG 记的既有债：今天 `it?.let{}` 跳过 null，旧设备的流会残留。
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>("abc")
        val vm = LogcatViewModel(controller, recordingManager(runner, backgroundScope), selected, MutableStateFlow(true), this)
        vm.onPageEntered(); advanceUntilIdle()
        assertEquals(LogcatStatus.RUNNING, vm.status.value)
        selected.value = null
        advanceUntilIdle()
        assertEquals(LogcatStatus.IDLE, vm.status.value, "deselecting must stop the stream")
        vm.stop(); controller.stop()
    }

    @Test fun startRecording_without_device_sets_inline_error() = runTest {
        val vm = LogcatViewModel(controller(FakeAdbProcessRunner(), backgroundScope),
            recordingManager(FakeAdbProcessRunner(), backgroundScope),
            MutableStateFlow(null), MutableStateFlow(false), backgroundScope)
        vm.startRecording()
        assertEquals(true, vm.recordError.value != null)
        assertEquals(ManualRecordState.Idle, vm.manualRecord.value)
    }

    @Test fun start_stop_recording_transitions_manual_state() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: m"))
        val rec = recordingManager(runner, this)
        val serial = MutableStateFlow("abc")
        val vm = LogcatViewModel(controller(runner, this),
            rec, serial, MutableStateFlow(true), this)
        vm.startRecording()
        advanceUntilIdle()
        assertTrue(vm.manualRecord.value is ManualRecordState.Recording)
        vm.stopRecording()
        advanceUntilIdle()
        val st = vm.manualRecord.value
        assertTrue(st is ManualRecordState.Stopped)
        assertEquals("user", st.reason)
        vm.stop()
        rec.stop()
        advanceUntilIdle()
    }

    @Test fun latestRecordingFile_returns_newest_log_and_null_when_empty() = runTest {
        // 常驻定位按钮数据源（点击时实时取，不缓存）：目录有文件 → 最新 .log；空 → null（UI 转而打开目录）
        val runner = FakeAdbProcessRunner()
        val rec = recordingManager(runner, backgroundScope)
        val older = rec.recordingsDir.resolve("a.log"); older.writeText("x")
        val newer = rec.recordingsDir.resolve("b.log"); newer.writeText("x")
        newer.toFile().setLastModified(System.currentTimeMillis() + 60_000L)
        val vm = LogcatViewModel(controller(runner, backgroundScope),
            rec, MutableStateFlow("abc"), MutableStateFlow(true), backgroundScope)
        advanceUntilIdle()
        assertEquals(newer, vm.latestRecordingFile())
        rec.stop()
        advanceUntilIdle()
    }
}
