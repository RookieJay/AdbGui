package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.adb.FakeCdpTransport
import com.adbgui.core.device.CdpController
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.CdpConnectionState
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** State-machine tests for [CdpDebugViewModel] — mirrors [PortForwardingViewModelTest]'s
 *  `makeVm` + `try{...}finally{ controller.stop() }` pattern. The VM is a thin wrapper over
 *  [CdpController]; these tests pin the state flows + control methods + auto-start/stop on
 *  serial change.
 *
 *  All tests use a non-null initial serial + scripted webview-socket/forward so the VM's
 *  auto-collector fires `controller.start` (not `controller.stop`, which would close the
 *  FakeCdpTransport's channel and strand later emits). The Target.getTargets response is left
 *  pending — `start`'s runJob suspends on that await with `state==CONNECTED` already set, which
 *  is all these state-machine assertions need; `controller.stop()` in teardown drains it. */
class CdpDebugViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun makeVm(
        transport: FakeCdpTransport,
        runner: FakeAdbProcessRunner,
        selected: MutableStateFlow<String?>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): CdpDebugViewModel {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val ctrl = CdpController(transport, cmd, NoopLogger, scope)
        return CdpDebugViewModel(ctrl, selected, scope)
    }

    /** Script the webview-socket probe + forward so one-click `start` reaches the connect phase. */
    private fun scriptStartPrereqs(runner: FakeAdbProcessRunner) {
        runner.whenArgsContains(listOf("shell", "cat /proc/net/unix"),
            AdbProcessResult(0, "@webview_devtools_remote_42\n", ""))
        runner.whenArgsContains(listOf("forward", "tcp:9222"), AdbProcessResult(0, "", ""))
    }

    @Test
    fun start_on_serial_select_connects() = runTest {
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val selected = MutableStateFlow<String?>("s1")
        val vm = makeVm(transport, runner, selected, this)
        try {
            advanceUntilIdle()      // collector fires start("s1"): socket probe + forward + connect (CONNECTED) + runLoop, then suspends on Target.getTargets await
            assertEquals(CdpConnectionState.CONNECTED, vm.state.value)
            assertNull(vm.error.value)
        } finally { vm.stop() }
    }

    @Test
    fun console_grows_when_device_emits_event() = runTest {
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val selected = MutableStateFlow<String?>("s1")
        val vm = makeVm(transport, runner, selected, this)
        try {
            advanceUntilIdle()      // start("s1") → runLoop collecting on the open channel
            transport.emit("""{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"value":"hi"}]}}""")
            advanceUntilIdle()
            assertEquals(1, vm.consoleEntries.value.size)
            assertEquals("hi", vm.consoleEntries.value[0].text)
        } finally { vm.stop() }
    }

    @Test
    fun evaluate_result_surfaces_to_evalResult_flow() = runTest {
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val selected = MutableStateFlow<String?>("s1")
        val vm = makeVm(transport, runner, selected, this)
        try {
            advanceUntilIdle()
            vm.evaluate("1+1", null); advanceUntilIdle()
            val sent = transport.sent.last { it.contains("Runtime.evaluate") }
            val id = sent.substringAfter("\"id\":").substringBefore(',').toInt()
            transport.emit("""{"id":$id,"result":{"result":{"type":"number","value":2,"description":"2"}}}""")
            advanceUntilIdle()
            assertNotNull(vm.evalResult.value)
            assertEquals("2", vm.evalResult.value!!.value)
            assertNull(vm.evalResult.value!!.exception)
        } finally { vm.stop() }
    }

    @Test
    fun connectManual_failure_surfaces_inline_error() = runTest {
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val selected = MutableStateFlow<String?>("s1")
        val vm = makeVm(transport, runner, selected, this)
        try {
            advanceUntilIdle()              // start("s1"): first connect succeeds (CONNECTED), suspends on targets await
            transport.connectShouldFail = true
            vm.connectManual(9222)           // cancels start's runJob; new runJob's connect fails
            advanceUntilIdle()
            assertNotNull(vm.error.value)
            assertEquals(CdpConnectionState.FAILED, vm.state.value)
        } finally { vm.stop() }
    }

    @Test
    fun stop_on_serial_clear_disconnects() = runTest {
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val selected = MutableStateFlow<String?>("s1")
        val vm = makeVm(transport, runner, selected, this)
        try {
            advanceUntilIdle()
            assertEquals(CdpConnectionState.CONNECTED, vm.state.value)
            selected.value = null; advanceUntilIdle()   // collector fires controller.stop() → transport.close()
            assertEquals(CdpConnectionState.DISCONNECTED, vm.state.value)
        } finally { vm.stop() }
    }

    @Test
    fun auto_restart_on_serial_change_reprobes_socket() = runTest {
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val selected = MutableStateFlow<String?>("A")
        val vm = makeVm(transport, runner, selected, this)
        try {
            advanceUntilIdle()
            selected.value = "B"; advanceUntilIdle()
            // Auto-restart: the collector fired start("A") then start("B") on the change; each
            // start issues a webview-socket probe (`cat /proc/net/unix`) → 2 probes.
            val probes = runner.runs.count { it.contains("cat /proc/net/unix") }
            assertEquals(2, probes, "expected a socket probe per serial; got: ${runner.runs}")
        } finally { vm.stop() }
    }

    @Test
    fun clearConsole_empties_console_entries() = runTest {
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val selected = MutableStateFlow<String?>("s1")
        val vm = makeVm(transport, runner, selected, this)
        try {
            advanceUntilIdle()
            transport.emit("""{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"value":"hi"}]}}""")
            advanceUntilIdle()
            assertEquals(1, vm.consoleEntries.value.size)
            vm.clearConsole()
            assertTrue(vm.consoleEntries.value.isEmpty())
        } finally { vm.stop() }
    }
}
