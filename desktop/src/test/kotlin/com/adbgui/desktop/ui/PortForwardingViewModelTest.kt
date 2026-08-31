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
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardSpec
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortForwardingViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun makeVm(
        runner: FakeAdbProcessRunner,
        selected: MutableStateFlow<String?>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<DeviceRepository, PortForwardingViewModel> {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("pf"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return repo to PortForwardingViewModel(repo, selected, scope)
    }

    @Test
    fun refresh_loads_and_filters_forwards_for_selected_serial() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"),
            AdbProcessResult(0, "s1 tcp:9222 localabstract:foo\ns2 tcp:8080 localabstract:bar\n", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("s1"), this)
        try {
            advanceUntilIdle()
            assertEquals(1, vm.forwards.value.size)
            assertEquals("s1", vm.forwards.value[0].serial)
        } finally { vm.stop(); repo.stop() }
    }

    @Test
    fun add_calls_forward_then_refreshes_list() = runTest {
        val runner = FakeAdbProcessRunner()
        // forward command (serial, exits 0); then --list shows the new row
        runner.whenArgsContains(listOf("forward", "tcp:9222", "localabstract:foo"), AdbProcessResult(0, "", ""))
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "s1 tcp:9222 localabstract:foo\n", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("s1"), this)
        try {
            vm.setLocalValue("9222"); vm.setRemoteType(ForwardEndpointType.LOCALABSTRACT); vm.setRemoteValue("foo")
            vm.add(); advanceUntilIdle()
            assertEquals(1, vm.forwards.value.size)
            assertEquals("tcp:9222", vm.forwards.value[0].local.adbForm())
            assertNull(vm.error.value)
        } finally { vm.stop(); repo.stop() }
    }

    @Test
    fun add_failure_sets_inline_error_keeps_list() = runTest {
        val runner = FakeAdbProcessRunner()
        // forward fails; --list returns a pre-existing row so the list isn't wiped on error
        runner.whenArgsContains(listOf("forward", "tcp:9222", "localabstract:foo"), AdbProcessResult(1, "", "cannot bind socket"))
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "s1 tcp:8080 localabstract:bar\n", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("s1"), this)
        try {
            vm.setLocalValue("9222"); vm.setRemoteType(ForwardEndpointType.LOCALABSTRACT); vm.setRemoteValue("foo")
            vm.add(); advanceUntilIdle()
            assertTrue(vm.error.value!!.contains("adb"))
            assertEquals(1, vm.forwards.value.size, "list must still reflect the refresh after add failure")
        } finally { vm.stop(); repo.stop() }
    }

    @Test
    fun add_with_blank_local_value_sets_error_without_calling_adb() = runTest {
        val runner = FakeAdbProcessRunner()
        // No script for forward tcp:9222 — if the VM wrongly calls adb, the default exit-1 would set a different error.
        val (repo, vm) = makeVm(runner, MutableStateFlow("s1"), this)
        try {
            vm.add(); advanceUntilIdle()
            assertTrue(vm.error.value!!.isNotBlank())
            assertTrue(vm.forwards.value.isEmpty())
        } finally { vm.stop(); repo.stop() }
    }

    @Test
    fun remove_calls_removeForward_then_refreshes() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--remove", "tcp:9222"), AdbProcessResult(0, "", ""))
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("s1"), this)
        try {
            vm.remove(ForwardSpec(ForwardEndpointType.TCP, "9222")); advanceUntilIdle()
            assertTrue(vm.forwards.value.isEmpty())
        } finally { vm.stop(); repo.stop() }
    }

    @Test
    fun auto_refreshes_when_selected_serial_changes() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "sX tcp:1 localabstract:a\n", ""))
        val selected = MutableStateFlow<String?>(null)
        val (repo, vm) = makeVm(runner, selected, this)
        try {
            advanceUntilIdle()
            assertTrue(vm.forwards.value.isEmpty())
            selected.value = "sX"; advanceUntilIdle()
            assertEquals(1, vm.forwards.value.size)
        } finally { vm.stop(); repo.stop() }
    }
}
