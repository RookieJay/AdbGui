package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.IDeviceTracker
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.Extra
import com.adbgui.core.domain.ExtraType
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConsoleViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun vm(runner: FakeAdbProcessRunner, selected: MutableStateFlow<String?>, scope: kotlinx.coroutines.CoroutineScope): Pair<DeviceRepository, AppConsoleViewModel> {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("ac"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return repo to AppConsoleViewModel(repo, selected, scope)
    }

    @Test fun install_success_sets_message() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install"), AdbProcessResult(0, "Success\n", ""))
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.install("C:/x/test.apk"); advanceUntilIdle()
        val msg = vm.message.value
        assertTrue(msg != null && msg.contains("test.apk"), "expected success message with apk name, got: $msg")
        vm.stop(); repo.stop()
    }

    @Test fun install_failure_sets_error_and_no_message() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install"), AdbProcessResult(0, "Failure [INSTALL_FAILED_OLDER_SDK]\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.install("C:/x/test.apk"); advanceUntilIdle()
        assertTrue(vm.error.value != null, "expected error on install failure")
        assertTrue(vm.message.value == null, "no success message on failure")
        vm.stop(); repo.stop()
    }

    @Test fun load_lists_packages() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\npackage:com.bar\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.load(); advanceUntilIdle()
        assertEquals(2, vm.packages.value.size)
        vm.stop(); repo.stop()
    }

    @Test fun forceStop_sends_command() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("force-stop"), AdbProcessResult(0, "", ""))
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.forceStop("com.foo"); advanceUntilIdle()
        vm.stop(); repo.stop()
    }

    @Test fun startApp_sends_monkey() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("monkey"), AdbProcessResult(0, "", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.startApp("com.foo"); advanceUntilIdle()
        vm.stop(); repo.stop()
    }

    @Test fun sendBroadcast_returns_result() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("broadcast"), AdbProcessResult(0, "Broadcasting Intent { act=com.test }", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.sendBroadcast("com.test.ACTION", null, listOf(Extra(ExtraType.STRING, "k", "v"))); advanceUntilIdle()
        assertTrue(vm.broadcastResult.value?.contains("Broadcasting") == true)
        vm.stop(); repo.stop()
    }

    @Test fun queryProvider_returns_result() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("content", "query"), AdbProcessResult(0, "Row: 0 _id=1\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.queryProvider("content://settings/system", null); advanceUntilIdle()
        assertTrue(vm.providerResult.value?.contains("Row:") == true)
        vm.stop(); repo.stop()
    }
}
