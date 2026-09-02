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
import kotlinx.coroutines.test.runCurrent
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
    fun start_populates_targets_from_gettargets_response() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell", "cat /proc/net/unix"),
            AdbProcessResult(0, "@webview_devtools_remote_42\n", ""))
        runner.whenArgsContains(listOf("forward", "tcp:9222"), AdbProcessResult(0, "", ""))
        val transport = FakeCdpTransport()
        val (_, ctrl) = makeController(transport, runner, this)
        val job = launch { ctrl.start("s1") }
        advanceUntilIdle()   // socket probe + forward + connect + Target.getTargets sent (awaiting)
        // Capture the id the controller allocated for Target.getTargets, then emit the response.
        // CDP wraps the payload under "result": {"id":N,"result":{"targetInfos":[...]}}.
        val sentReq = transport.sent.last { it.contains("Target.getTargets") }
        val id = sentReq.substringAfter("\"id\":").substringBefore(',').toInt()
        transport.emit("""{"id":$id,"result":{"targetInfos":[{"type":"page","targetId":"PAGE1","title":"t","url":"u"}]}}""")
        advanceUntilIdle()
        assertEquals(1, ctrl.targets.value.size, "targets must be populated from the response")
        assertEquals("PAGE1", ctrl.targets.value[0].targetId)
        ctrl.stop()
        job.cancel()
    }

    @Test
    fun getResponseBody_unwraps_result_body() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        val job = async { ctrl.getResponseBody("r1") }
        runCurrent()  // let cdpSend register pending + send the request WITHOUT advancing virtual time (the 5s withTimeout would fire under advanceUntilIdle)
        val sentReq = transport.sent.last { it.contains("Network.getResponseBody") }
        val id = sentReq.substringAfter("\"id\":").substringBefore(',').toInt()
        // CDP wraps the body under "result": {"id":N,"result":{"body":"hello","base64Encoded":false}}.
        transport.emit("""{"id":$id,"result":{"body":"hello","base64Encoded":false}}""")
        advanceUntilIdle()  // deliver the response → complete the await (before the 5s timeout)
        val body = job.await()
        assertTrue(body is com.adbgui.core.domain.CdpResponseBody.Body, "expected Body, got $body")
        assertEquals("hello", (body as com.adbgui.core.domain.CdpResponseBody.Body).text, "body must be unwrapped from result")
        ctrl.stop()
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
    fun clearError_nulls_the_error_message() = runTest {
        val transport = FakeCdpTransport()
        // make connect throw → connectAndRun sets _error
        transport.connectShouldFail = true
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        assertTrue(ctrl.error.value != null, "expected an error after connect failure; got: ${ctrl.error.value}")
        ctrl.clearError()
        assertNull(ctrl.error.value, "clearError must null out the error message")
        ctrl.stop()
    }

    @Test
    fun clearConsole_empties_the_ring_buffer() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        transport.emit("""{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"value":"hi"}]}}""")
        advanceUntilIdle()
        assertEquals(1, ctrl.consoleEntries.value.size)
        ctrl.clearConsole()
        assertTrue(ctrl.consoleEntries.value.isEmpty())
        ctrl.stop()
    }

    @Test
    fun clearNetwork_empties_the_request_list() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        transport.emit("""{"method":"Network.requestWillBeSent","params":{"requestId":"r1","request":{"method":"GET","url":"http://x"}}}""")
        advanceUntilIdle()
        assertEquals(1, ctrl.networkRequests.value.size)
        ctrl.clearNetwork()
        assertTrue(ctrl.networkRequests.value.isEmpty())
        ctrl.stop()
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

    // I1: two identical console frames must produce entries with DISTINCT ids so the LazyColumn
    // key (it.id) is unique — hashCode key crashed on duplicate lines.
    @Test
    fun console_entries_get_distinct_ids_for_identical_frames() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        transport.emit("""{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"value":"x"}]}}""")
        transport.emit("""{"method":"Runtime.consoleAPICalled","params":{"type":"log","args":[{"value":"x"}]}}""")
        advanceUntilIdle()
        assertEquals(2, ctrl.consoleEntries.value.size)
        val id0 = ctrl.consoleEntries.value[0].id
        val id1 = ctrl.consoleEntries.value[1].id
        assertTrue(id0 != id1, "identical console frames must get distinct ids; got $id0 and $id1")
        ctrl.stop()
    }

    // I3 (revised): one-click→manual switch does NOT remove the prior forward — `start()` sets up the
    // new forward BEFORE connectAndRun, so removing "the prior forward" (same local port tcp:9222)
    // would remove the new one → Connection refused. Forward cleanup is `stop()`'s job. The
    // one-click→manual-different-port leak is a deferred minor (rare). What I3 DOES do: drain the
    // prior session's pending (tested elsewhere). Here we pin the no-remove-on-switch behavior.
    @Test
    fun connectManual_after_start_does_not_remove_forward_on_switch() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell", "cat /proc/net/unix"),
            AdbProcessResult(0, "@webview_devtools_remote_1\n", ""))
        runner.whenArgsContains(listOf("forward", "tcp:9222"), AdbProcessResult(0, "", ""))
        runner.whenArgsContains(listOf("forward", "--remove"), AdbProcessResult(0, "", ""))
        val transport = FakeCdpTransport()
        val (_, ctrl) = makeController(transport, runner, this)
        val job = launch { ctrl.start("s1") }
        advanceUntilIdle()
        ctrl.connectManual(9222)
        advanceUntilIdle()
        // The switch must NOT have issued --remove (that would remove the forward start() just set).
        assertTrue(runner.runs.none { it.contains("--remove") },
            "start→connectManual must not remove the forward (would remove the new one); got: ${runner.runs}")
        ctrl.stop()
        job.cancel()
    }

    // C1: on a drop (state→DISCONNECTED while setupComplete), the state-observer sets
    // RECONNECTING, backs off, re-connects the page ws, re-enables domains, returns to CONNECTED.
    @Test
    fun reconnect_on_drop_sets_reconnecting_then_reconnects() = runTest {
        val transport = FakeCdpTransport()
        val runner = FakeAdbProcessRunner()
        val (_, ctrl) = makeController(transport, runner, this)
        ctrl.connectManual(9222); advanceUntilIdle()
        // Script the Target.getTargets response so setup completes (setupComplete = true,
        // page ws connected). Domain enables are still awaiting responses — that's fine;
        // setupComplete is set right after the page ws connect.
        val sentReq = transport.sent.last { it.contains("Target.getTargets") }
        val id = sentReq.substringAfter("\"id\":").substringBefore(',').toInt()
        transport.emit("""{"id":$id,"result":{"targetInfos":[{"type":"page","targetId":"PAGE1","title":"t","url":"u"}]}}""")
        advanceUntilIdle()
        // Setup complete: state CONNECTED, at least 1 domain enable sent
        assertEquals(CdpConnectionState.CONNECTED, ctrl.connectionState.value)
        val sentBefore = transport.sent.size
        assertTrue(sentBefore >= 2, "expected at least Target.getTargets + 1 domain enable; got $sentBefore")
        // Simulate a drop — state→DISCONNECTED (channel stays open for recovery)
        transport.simulateDrop()
        runCurrent()  // state observer sees DISCONNECTED, enters driveReconnect, _connectionState=RECONNECTING
        assertEquals(CdpConnectionState.RECONNECTING, ctrl.connectionState.value,
            "expected RECONNECTING during backoff; got ${ctrl.connectionState.value}")
        advanceUntilIdle()  // backoff completes, reconnect succeeds
        assertEquals(CdpConnectionState.CONNECTED, ctrl.connectionState.value,
            "expected CONNECTED after reconnect; got ${ctrl.connectionState.value}")
        // Reconnect re-sent 4 domain enables
        val sentAfter = transport.sent.size
        assertTrue(sentAfter >= sentBefore + 4,
            "reconnect should re-send 4 domain enables; before=$sentBefore after=$sentAfter")
        ctrl.stop()
    }
}
