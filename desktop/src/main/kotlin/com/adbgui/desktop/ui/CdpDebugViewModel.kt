package com.adbgui.desktop.ui

import com.adbgui.core.device.CdpController
import com.adbgui.core.domain.CdpConnectionState
import com.adbgui.core.domain.CdpConsoleEntry
import com.adbgui.core.domain.CdpEvalResult
import com.adbgui.core.domain.CdpNetworkRequest
import com.adbgui.core.domain.CdpTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Thin VM over [CdpController] for the CDP Debug page. Delegates the controller's StateFlows
 *  straight through (no copy — the controller owns the ring buffers / state machine) and wraps
 *  the controller's suspend control methods in `scope.launch` so UI callbacks (non-suspend) can
 *  fire them. One long-lived collector watches `selectedSerial`: a non-null serial auto-starts
 *  a one-click session (`controller.start`); null auto-stops it (`controller.stop`). Uses
 *  `collectLatest` so a rapid A→B switch cancels A's in-flight start before B's runs.
 *
 *  Red line #2: the VM injects [CdpController] (`:core`) only — never ktor / CommandRunner. */
class CdpDebugViewModel(
    private val controller: CdpController,
    private val selectedSerial: StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    // Delegated straight from the controller — it owns the state machine + ring buffers.
    val consoleEntries: StateFlow<List<CdpConsoleEntry>> get() = controller.consoleEntries
    val networkRequests: StateFlow<List<CdpNetworkRequest>> get() = controller.networkRequests
    val targets: StateFlow<List<CdpTarget>> get() = controller.targets
    val state: StateFlow<CdpConnectionState> get() = controller.state
    val error: StateFlow<String?> get() = controller.error

    // Results the UI collects (eval / response body) — surfaced as StateFlows so a `scope.launch`
    // wrapper can publish them without making the UI callback suspend.
    private val _evalResult = MutableStateFlow<CdpEvalResult?>(null)
    val evalResult: StateFlow<CdpEvalResult?> = _evalResult.asStateFlow()
    private val _responseBody = MutableStateFlow<String?>(null)
    val responseBody: StateFlow<String?> = _responseBody.asStateFlow()
    private val _selectedTargetId = MutableStateFlow<String?>(null)
    val selectedTargetId: StateFlow<String?> = _selectedTargetId.asStateFlow()

    // The only long-lived collector: auto-start/stop on serial change. `collectLatest` cancels the
    // previous emission's work — a stale `start` from device A is cancelled before B's runs. It is
    // a child of [scope]; [stop] cancels it (and the controller's runJob child) on teardown.
    private val collector: Job = scope.launch {
        selectedSerial.collectLatest { serial ->
            if (serial != null) controller.start(serial) else controller.stop()
        }
    }

    fun start(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        controller.start(serial)
    }

    fun connectManual(port: Int): Job = scope.launch { controller.connectManual(port) }

    /** Stop the CDP session: cancel the serial collector (which cascades to the controller's run
     *  loop + transport via structured concurrency) and drain in-flight callers / remove the
     *  forward via `controller.stop()`. The drain runs as a child of [scope]; the returned [Job]
     *  completes when teardown finishes. After [stop] the VM no longer auto-restarts on serial
     *  change — recreate the VM (or don't call [stop]) to keep the collector alive. */
    fun stop(): Job {
        collector.cancel()
        return scope.launch { controller.stop() }
    }

    fun evaluate(expr: String, frame: String?): Job = scope.launch {
        _evalResult.value = controller.evaluate(expr, frame)
    }

    fun reload(): Job = scope.launch { controller.reload() }

    fun getResponseBody(requestId: String): Job = scope.launch {
        _responseBody.value = controller.getResponseBody(requestId)
    }

    fun clearConsole() = controller.clearConsole()

    fun selectTarget(targetId: String) { _selectedTargetId.value = targetId }
}
