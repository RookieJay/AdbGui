package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.LogcatController
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogcatViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test fun selected_serial_change_starts_logcat_and_lines_flow() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>(null)
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(true), this)
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
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(true), this)
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
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(true), this)
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
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(true), this)
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
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(true), this)
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
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(false), this)
        val tmp = Files.createTempFile("logcat-export", ".txt")
        vm.exportFull(tmp.toString())
        advanceUntilIdle()
        assertNotNull(vm.exportError.value)
        assertEquals(false, vm.exporting.value)
        vm.stop(); controller.stop()
    }
}
