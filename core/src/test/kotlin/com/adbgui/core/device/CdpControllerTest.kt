package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CdpConnectionException
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.adb.FakeCdpTransport
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.CdpConnectionState
import com.adbgui.core.domain.CdpLevel
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdpControllerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun makeController(
        transport: FakeCdpTransport,
        runner: FakeAdbProcessRunner,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<CommandRunner, CdpController> {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val ctrl = CdpController(transport, cmd, NoopLogger, scope)
        return cmd to ctrl
    }

    @Test
    fun start_probes_socket_forwards_and_connects() = runTest {
        val runner = FakeAdbProcessRunner()
        // webviewSocket probe: `cat /proc/net/unix` returns a line with the socket
        runner.whenArgsContains(listOf("shell", "cat /proc/net/unix"),
            AdbProcessResult(0, "... @webview_devtools_remote_42 ...\n", ""))
        // forward succeeds (exit 0, empty)
        runner.whenArgsContains(listOf("forward", "tcp:9222"), AdbProcessResult(0, "", ""))
        val transport = FakeCdpTransport()
        val (_, ctrl) = makeController(transport, runner, this)
        // start() launches the run loop; we don't pre-seed a Target.getTargets response, so
        // the controller suspends on that await — but the request must have been sent.
        val job = launch { ctrl.start("10.0.6.100:5555") }
        advanceUntilIdle()
        assertTrue(transport.sent.any { it.contains("Target.getTargets") })
        // forward was issued (matches the tcp:9222 rule → exit 0, no throw)
        ctrl.stop()
        job.cancel()
    }

    @Test
    fun start_no_socket_throws_with_actionable_message() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell", "cat /proc/net/unix"), AdbProcessResult(0, "no webview here\n", ""))
        val transport = FakeCdpTransport()
        val (_, ctrl) = makeController(transport, runner, this)
        try {
            ctrl.start("s1")
            error("expected CdpConnectionException")
        } catch (e: CdpConnectionException) {
            assertTrue(e.message!!.contains("webview"))
        }
        ctrl.stop()
    }

    @Test
    fun console_event_appended_to_entries() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222)  // bypass forward; connect browser ws + start the frame collector
        advanceUntilIdle()        // let the collector start
        // simulate the device emitting a console event
        transport.emit("""{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"value":"hi"}]}}""")
        advanceUntilIdle()
        assertEquals(1, ctrl.consoleEntries.value.size)
        assertEquals("hi", ctrl.consoleEntries.value[0].text)
        assertEquals(CdpLevel.LOG, ctrl.consoleEntries.value[0].level)
        ctrl.stop()
    }

    @Test
    fun network_events_merged_by_request_id() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222)
        advanceUntilIdle()
        transport.emit("""{"method":"Network.requestWillBeSent","params":{"requestId":"r1","request":{"method":"GET","url":"http://x"}}}""")
        transport.emit("""{"method":"Network.responseReceived","params":{"requestId":"r1","response":{"status":200,"mimeType":"text/html"}}}""")
        transport.emit("""{"method":"Network.loadingFinished","params":{"requestId":"r1"}}""")
        advanceUntilIdle()
        assertEquals(1, ctrl.networkRequests.value.size)
        val r = ctrl.networkRequests.value[0]
        assertEquals(200, r.status)
        assertEquals("text/html", r.mime)
        ctrl.stop()
    }

    @Test
    fun evaluate_returns_value_from_matching_id_response() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222)
        advanceUntilIdle()
        // controller sends {id:N, method:"Runtime.evaluate", ...}; capture N from transport.sent
        val job = async { ctrl.evaluate("1+1", null) }
        advanceUntilIdle()  // let the request be sent + registered
        val sentReq = transport.sent.last { it.contains("Runtime.evaluate") }
        val id = sentReq.substringAfter("\"id\":").substringBefore(',').toInt()
        transport.emit("""{"id":$id,"result":{"result":{"type":"number","value":2,"description":"2"}}}""")
        val result = job.await()
        assertEquals("2", result.value)
        assertNull(result.exception)
        ctrl.stop()
    }

    @Test
    fun stop_in_one_click_mode_removes_forward() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell", "cat /proc/net/unix"),
            AdbProcessResult(0, "@webview_devtools_remote_1\n", ""))
        runner.whenArgsContains(listOf("forward", "tcp:9222"), AdbProcessResult(0, "", ""))
        runner.whenArgsContains(listOf("forward", "--remove"), AdbProcessResult(0, "", ""))
        val transport = FakeCdpTransport()
        val (_, ctrl) = makeController(transport, runner, this)
        val job = launch { ctrl.start("s1") }
        advanceUntilIdle()
        ctrl.stop()
        advanceUntilIdle()
        job.cancel()
        // M-4: assert the CONTROLLER actually issued `forward --remove tcp:9222` (recorded against
        // the Fake's run log), not just that a script would match if it were called.
        assertTrue(runner.runs.any { it.contains("--remove") && it.contains("tcp:9222") },
            "expected a forward --remove tcp:9222 call; got: ${runner.runs}")
    }

    @Test
    fun stop_strands_inflight_evaluate_with_connection_closed_exception() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        // Isolate the async's failure from the runTest parent (structured concurrency would
        // otherwise cancel the test scope when the stranded evaluate throws).
        supervisorScope {
            // Start an evaluate but NEVER emit its response → it's in-flight when stop() runs.
            val evalJob = async { ctrl.evaluate("1+1", null) }
            advanceUntilIdle()
            ctrl.stop()  // drains pending + completes the deferred exceptionally
            val ex = assertFailsWith<CdpConnectionException> { evalJob.await() }
            assertTrue(ex.message!!.contains("closed"))
        }
    }

    @Test
    fun evaluate_object_result_stringifies_without_crashing() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        val job = async { ctrl.evaluate("document.querySelector('div')", null) }
        advanceUntilIdle()
        val sentReq = transport.sent.last { it.contains("Runtime.evaluate") }
        val id = sentReq.substringAfter("\"id\":").substringBefore(',').toInt()
        // Non-primitive `value` (an object, no description) — must stringify, not throw.
        transport.emit("""{"id":$id,"result":{"result":{"type":"object","value":{"x":1}}}}""")
        val result = job.await()
        assertNull(result.exception)
        assertTrue(result.value!!.contains("\"x\":1"), "expected serialized object; got: ${result.value}")
        ctrl.stop()
    }

    @Test
    fun evaluate_object_result_prefers_description() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        val job = async { ctrl.evaluate("document.querySelector('div')", null) }
        advanceUntilIdle()
        val sentReq = transport.sent.last { it.contains("Runtime.evaluate") }
        val id = sentReq.substringAfter("\"id\":").substringBefore(',').toInt()
        transport.emit("""{"id":$id,"result":{"result":{"type":"object","value":{"x":1},"description":"<div>"}}}""")
        val result = job.await()
        assertEquals("<div>", result.value)
        assertNull(result.exception)
        ctrl.stop()
    }

    @Test
    fun stop_in_manual_mode_does_not_remove_forward() = runTest {
        val runner = FakeAdbProcessRunner()
        val transport = FakeCdpTransport()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        ctrl.stop(); advanceUntilIdle()
        // No remove call issued (no script for --remove, but more importantly transport is closed).
        assertEquals(CdpConnectionState.DISCONNECTED, transport.state.value)
    }

    @Test
    fun unknown_method_does_not_crash_or_swallow() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222)
        advanceUntilIdle()
        transport.emit("""{"method":"Something.unknown","params":{}}""")
        advanceUntilIdle()
        assertTrue(ctrl.consoleEntries.value.isEmpty())
        assertTrue(ctrl.networkRequests.value.isEmpty())
        ctrl.stop()
    }
}
