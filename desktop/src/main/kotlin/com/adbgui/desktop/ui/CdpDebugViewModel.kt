package com.adbgui.desktop.ui

import com.adbgui.core.device.CdpController
import com.adbgui.core.domain.CdpConnectionState
import com.adbgui.core.domain.CdpConsoleEntry
import com.adbgui.core.domain.CdpEvalResult
import com.adbgui.core.domain.CdpNetworkRequest
import com.adbgui.core.domain.CdpResponseBody
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
 *  fire them. A page-scoped collector watches `selectedSerial` while the CDP page is visible
 *  (mounted by [onPageEntered], cancelled by [stop]): a non-null serial auto-starts a one-click
 *  session (`controller.start`); null auto-stops it (`controller.stop`). Uses `collectLatest` so
 *  a rapid A→B switch cancels A's in-flight start before B's runs.
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
    private val _responseBody = MutableStateFlow<CdpResponseBody?>(null)
    val responseBody: StateFlow<CdpResponseBody?> = _responseBody.asStateFlow()
    private val _selectedTargetId = MutableStateFlow<String?>(null)
    val selectedTargetId: StateFlow<String?> = _selectedTargetId.asStateFlow()

    // Page-scoped collector: mounted by onPageEntered() when the CDP page becomes visible,
    // cancelled by stop() when it goes away. A0: this used to be an init-block collector, so
    // every app launch auto-opened a CDP session (adb forward + two websockets with
    // Runtime/Page/Network/Log enabled) for a page the user never opened. `collectLatest`
    // keeps the A->B rapid-switch semantics: A's in-flight start is cancelled before B's runs.
    private var pageJob: Job? = null

    /** 由 [CdpDebugScreen] 的 LaunchedEffect(Unit) 调用。是重建而非"只挂一次"，所以
     *  "离开页面 → 再回来"能重新连上（旧实现 stop() 永久取消 collector，VM 又是应用级
     *  单例，导致第二次进入永不自动连）。 */
    fun onPageEntered() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch {
            selectedSerial.collectLatest { serial ->
                if (serial != null) controller.start(serial) else controller.stop()
            }
        }
    }

    fun start(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        controller.start(serial)
    }

    fun connectManual(port: Int): Job = scope.launch { controller.connectManual(port) }

    /** 离开页面：取消页面级 collector（覆盖结构化并发下的 run loop + transport），并
     *  `controller.stop()` 关闭 ws + 移除自建的 forward（一键模式）。 */
    fun stop(): Job {
        pageJob?.cancel(); pageJob = null
        return scope.launch { controller.stop() }
    }

    fun evaluate(expr: String, frame: String?): Job = scope.launch {
        _evalResult.value = controller.evaluate(expr, frame)
    }

    fun reload(): Job = scope.launch { controller.reload() }

    fun getResponseBody(requestId: String): Job = scope.launch {
        _responseBody.value = null  // clear stale body from a prior row so the spinner shows
        _responseBody.value = controller.getResponseBody(requestId)
    }

    fun clearConsole() = controller.clearConsole()

    fun clearNetwork() = controller.clearNetwork()

    fun clearError() = controller.clearError()

    fun selectTarget(targetId: String) { _selectedTargetId.value = targetId }
}
