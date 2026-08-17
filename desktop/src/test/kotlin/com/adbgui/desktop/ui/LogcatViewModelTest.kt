package com.adbgui.desktop.ui

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.LogcatController
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogcatViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test fun selected_serial_change_starts_logcat_and_lines_flow() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>(null)
        val vm = LogcatViewModel(controller, selected, this)
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
        val vm = LogcatViewModel(controller, selected, this)
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
}
