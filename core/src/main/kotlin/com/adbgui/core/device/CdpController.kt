package com.adbgui.core.device

import com.adbgui.core.adb.CdpConnectionException
import com.adbgui.core.adb.CdpEventParser
import com.adbgui.core.adb.CdpTransport
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.domain.CdpConnectionState
import com.adbgui.core.domain.CdpConsoleEntry
import com.adbgui.core.domain.CdpEvalResult
import com.adbgui.core.domain.CdpEvent
import com.adbgui.core.domain.CdpNetState
import com.adbgui.core.domain.CdpNetworkRequest
import com.adbgui.core.domain.CdpResponseBody
import com.adbgui.core.domain.CdpTarget
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardSpec
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Drives a Chrome DevTools Protocol session over a [CdpTransport] (one-click `start` or manual
 *  `connectManual`). Mirrors [LogcatController]: a long-lived run loop on the injected [scope]
 *  collects inbound frames, an exponential-backoff reconnect (1s→30s cap, 3 fails → FAILED),
 *  a Mutex-serialized ring buffer for console entries, and `@Volatile` ownership flags.
 *
 *  Frames with an `id` are responses — routed to the pending request's [CompletableDeferred]
 *  (request/response correlation). Frames with a `method` are events — parsed by [CdpEventParser]
 *  (never throws; unknown methods logged at DEBUG, not swallowed) and applied to the StateFlows. */
class CdpController(
    private val transport: CdpTransport,
    private val commands: CommandRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val ringCap: Int = 10000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _console = MutableStateFlow<List<CdpConsoleEntry>>(emptyList())
    val consoleEntries: StateFlow<List<CdpConsoleEntry>> = _console.asStateFlow()
    private val _net = MutableStateFlow<List<CdpNetworkRequest>>(emptyList())
    val networkRequests: StateFlow<List<CdpNetworkRequest>> = _net.asStateFlow()
    private val _targets = MutableStateFlow<List<CdpTarget>>(emptyList())
    val targets: StateFlow<List<CdpTarget>> = _targets.asStateFlow()

    // C1: connectionState is a manually-managed MutableStateFlow updated by the state-observer
    // (runLoop) + connectAndRun catch blocks. When a drop is detected, the state-observer sets
    // RECONNECTING during backoff, then CONNECTED/FAILED on reconnect outcome. Without this,
    // the real transport's state goes DISCONNECTED on peer-drop but incoming.collect suspends
    // forever (incomingCh never closed) → reconnect was dead code.
    private val _connectionState = MutableStateFlow(CdpConnectionState.DISCONNECTED)
    val connectionState: StateFlow<CdpConnectionState> = _connectionState.asStateFlow()
    val state: StateFlow<CdpConnectionState> get() = connectionState

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // pending request id → CompletableDeferred for the matching response. Guarded by pendingMutex.
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
    private val pendingMutex = Mutex()
    private val nextId = AtomicInteger(1)
    private val idCounter = AtomicLong(0)   // monotonic id for CdpConsoleEntry (I1: stable LazyColumn key)

    private var runJob: Job? = null
    @Volatile private var ownsForward = false        // one-click mode → stop() removes the forward
    @Volatile private var currentSerial: String? = null
    @Volatile private var currentPageId: String? = null
    @Volatile private var currentPort: Int = 9222
    @Volatile private var setupComplete = false      // C1: state-observer only triggers reconnect after initial setup

    /** CDP domains to enable on connect + on reconnect. Enabling is what makes the WebView
     *  emit console/network events; re-enabling after a drop is what un-sticks event flow. */
    private val domainEnables = listOf("Runtime.enable", "Page.enable", "Network.enable", "Log.enable")

    /** One-click: probe the WebView socket → forward tcp:9222 → connect browser ws → pick page →
     *  enable domains. Throws [CdpConnectionException] with an actionable message if no socket. */
    suspend fun start(serial: String) {
        val socket = commands.webviewSocket(serial)
            ?: throw CdpConnectionException("无 webview socket — 目标应用需在前台运行且含 WebView")
        logger.info("[cdp] start: serial=$serial socket=$socket")
        commands.forward(serial,
            ForwardSpec(ForwardEndpointType.TCP, "9222"),
            ForwardSpec(ForwardEndpointType.LOCALABSTRACT, socket))
        logger.info("[cdp] forwarded tcp:9222 -> localabstract:$socket")
        connectAndRun(serial = serial, port = 9222, ownsForwardNew = true)
    }

    /** Manual: skip the forward (user set it up themselves), connect to
     *  ws://localhost:<port>/devtools/browser directly. */
    suspend fun connectManual(port: Int) {
        connectAndRun(serial = null, port = port, ownsForwardNew = false)
    }

    private suspend fun connectAndRun(serial: String?, port: Int, ownsForwardNew: Boolean) {
        logger.info("[cdp] connectAndRun: serial=$serial port=$port ownsForwardNew=$ownsForwardNew | prior ownsForward=$ownsForward priorSerial=$currentSerial priorPage=$currentPageId")
        // I3: drain in-flight callers from the prior session + cancel its runJob before starting a
        // new one. (Releases any `.await()`ing caller — e.g. a stranded Target.getTargets — with
        // "connection closed" so it doesn't hang.) We do NOT remove the prior forward here: `start()`
        // sets up the NEW forward BEFORE calling connectAndRun, so removing "the prior forward" would
        // actually remove the new one (same local port tcp:9222) → Connection refused. Forward cleanup
        // is `stop()`'s job (ownsForward). The one-click→manual-different-port leak is a deferred minor.
        val stranded = pendingMutex.withLock {
            val vals = pending.values.toList()
            pending.clear()
            vals
        }
        if (stranded.isNotEmpty()) logger.info("[cdp] I3: draining ${stranded.size} stranded pending -> connection closed")
        stranded.forEach { it.completeExceptionally(CdpConnectionException("connection closed")) }
        runJob?.cancel()
        ownsForward = ownsForwardNew
        currentSerial = serial
        currentPort = port
        runJob = scope.launch {
            try {
                setupComplete = false
                _connectionState.value = CdpConnectionState.CONNECTING
                logger.info("[cdp] connecting browser ws ws://localhost:$port/devtools/browser ...")
                transport.connect("ws://localhost:$port/devtools/browser")
                logger.info("[cdp] browser ws connected; sending Target.getTargets")
                launch { runLoop(port) }
                val targetsResp = cdpSend("Target.getTargets").await()
                val targetInfos = targetsResp["result"]?.jsonObject?.get("targetInfos")?.jsonArray
                val pages = targetInfos?.mapNotNull {
                    val o = it.jsonObject
                    if (o["type"]?.str() == "page") CdpTarget(
                        o["targetId"]?.str() ?: "", "page",
                        o["title"]?.str() ?: "", o["url"]?.str() ?: "")
                    else null
                } ?: emptyList()
                _targets.value = pages
                logger.info("[cdp] targets: ${pages.size} pages: ${pages.map { it.targetId }}")
                val page = pages.firstOrNull()
                    ?: throw CdpConnectionException("没有 page target（应用没在前台？）")
                currentPageId = page.targetId
                logger.info("[cdp] connecting page ws ws://localhost:$port/devtools/page/${page.targetId} ...")
                transport.connect("ws://localhost:$port/devtools/page/${page.targetId}")
                setupComplete = true
                logger.info("[cdp] setupComplete=true; enabling domains")
                domainEnables.forEach { cdpSend(it).await() }
                logger.info("[cdp] domains enabled; session CONNECTED")
            } catch (e: CdpConnectionException) {
                _error.value = e.message
                _connectionState.value = transport.state.value
                logger.info("[cdp] connectAndRun caught CdpConnectionException: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _error.value = t.message
                _connectionState.value = transport.state.value
                logger.warn("[cdp] connectAndRun error: ${t.message}")
            }
        }
    }

    /** C1: Run loop with state-observer reconnect. Two children under [supervisorScope]:
     *  1) Event dispatcher — collects [transport.incoming] and routes frames to [handleFrame].
     *     The persistent `incomingCh` is never closed on peer-drop (only `close()` closes it),
     *     so this suspends forever and resumes when a reconnect pumps new frames.
     *  2) State observer — watches [transport.state]; on DISCONNECTED/FAILED while a session is
     *     expected (wasConnected + setupComplete + currentPageId), calls [driveReconnect].
     *     This is the REAL drop detection path: KtorCdpTransport sets state=DISCONNECTED on
     *     peer-close but never closes incomingCh, so the dispatcher's collect never returns.
     *     Unifies Fake/real drop contract: both signal drops via state→DISCONNECTED. */
    private suspend fun runLoop(port: Int) = supervisorScope {
        // Event dispatcher
        launch {
            try {
                transport.incoming.collect { frame -> handleFrame(frame) }
                logger.warn("[cdp] incoming stream ended")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logger.warn("[cdp] dispatch error: ${t.message}")
                _error.value = t.message
            }
        }
        // State observer — drives reconnect on drop.
        launch {
            var wasConnected = false
            transport.state.collect { newState ->
                logger.info("[cdp] state -> $newState (wasConnected=$wasConnected setupComplete=$setupComplete page=$currentPageId)")
                when (newState) {
                    CdpConnectionState.CONNECTED -> {
                        wasConnected = true
                        _connectionState.value = newState
                    }
                    CdpConnectionState.DISCONNECTED, CdpConnectionState.FAILED -> {
                        if (wasConnected && setupComplete && currentPageId != null) {
                            wasConnected = false
                            _connectionState.value = CdpConnectionState.RECONNECTING
                            logger.info("[cdp] drop detected -> driveReconnect")
                            driveReconnect(port)
                        } else {
                            _connectionState.value = newState
                        }
                    }
                    else -> {
                        _connectionState.value = newState
                    }
                }
            }
        }
    }

    /** C1: Backoff reconnect (1s→30s cap, 3 fails → give up). _connectionState is already
     *  RECONNECTING (set by the state observer). On success, re-connects the page ws, sets
     *  CONNECTED, and re-enables domains so events flow again. On 3 failures, sets FAILED. */
    private suspend fun driveReconnect(port: Int) {
        var backoff = 1000L
        var failures = 0
        while (true) {
            delay(backoff)
            val page = currentPageId ?: break
            val pageUrl = "ws://localhost:$port/devtools/page/$page"
            val ok = runCatching { transport.connect(pageUrl) }.isSuccess
            logger.info("[cdp] reconnect attempt #$failures: $pageUrl ok=$ok")
            if (ok) {
                _connectionState.value = CdpConnectionState.CONNECTED
                domainEnables.forEach { runCatching { cdpSend(it) } }
                logger.info("[cdp] reconnected; re-enabled domains")
                return
            }
            failures++
            if (failures >= 3) break
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
        logger.info("[cdp] reconnect gave up after $failures failures -> FAILED")
        _connectionState.value = transport.state.value
    }

    private suspend fun handleFrame(frame: String) {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: run {
            logger.debug("[cdp] malformed frame (not JSON object): ${frame.take(120)}")
            return
        }
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (id != null) {
            logger.debug("[cdp] response id=$id")
            val completer = pendingMutex.withLock { pending.remove(id) }
            completer?.complete(obj)
            return
        }
        val method = obj["method"]?.str() ?: return
        logger.debug("[cdp] event: $method")
        val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
        val event = CdpEventParser.parseEvent(method, params)
        if (event == null) {
            logger.debug("[cdp] unknown CDP method: $method")
            return
        }
        applyEvent(event)
    }

    private suspend fun applyEvent(e: CdpEvent) {
        val now = fmtClock(clock())
        pendingMutex.withLock {
            when (e) {
                is CdpEvent.ConsoleAdd ->
                    // I1: assign a monotonic id so the LazyColumn key is stable even for
                    // identical console lines (hashCode collision → Compose IllegalArgumentException).
                    // timestamp = wall-clock arrival time, for manual review.
                    _console.value = (_console.value + e.entry.copy(id = idCounter.getAndIncrement(), timestamp = now)).takeLast(ringCap)
                is CdpEvent.NetRequest ->
                    _net.value = _net.value + e.req.copy(timestamp = now)
                is CdpEvent.NetResponse ->
                    _net.value = _net.value.map {
                        if (it.requestId == e.requestId)
                            it.copy(status = e.status, mime = e.mime, state = CdpNetState.RESPONSE)
                        else it
                    }
                is CdpEvent.NetDone ->
                    _net.value = _net.value.map {
                        if (it.requestId == e.requestId)
                            it.copy(state = if (e.ok) CdpNetState.DONE else CdpNetState.FAILED, error = e.error)
                        else it
                    }
            }
        }
    }

    private fun fmtClock(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(ms))

    /** Send a CDP request; return a [CompletableDeferred] that completes with the matching
     *  response object (routed by [handleFrame] via `id`). */
    private suspend fun cdpSend(method: String, params: JsonObject = JsonObject(emptyMap())): CompletableDeferred<JsonObject> {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingMutex.withLock { pending[id] = deferred }
        val req = buildJsonObject {
            put("id", id)
            put("method", method)
            if (params.isNotEmpty()) put("params", params)
        }
        transport.send(req.toString())
        return deferred
    }

    /** Evaluate `expr` (optionally in a `frame` JS expression context) and return the value or
     *  JS exception description. */
    suspend fun evaluate(expr: String, frame: String?): CdpEvalResult {
        val params = if (frame == null) buildJsonObject {
            put("expression", expr)
            put("returnByValue", true)
            put("awaitPromise", true)
        } else buildJsonObject {
            put("expression", "($frame).eval(${JsonPrimitive(expr)})")
            put("returnByValue", true)
        }
        val resp = cdpSend("Runtime.evaluate", params).await()
        val resultObj = resp["result"]?.jsonObject
        val exc = resultObj?.get("exceptionDetails")
        return if (exc != null) {
            val desc = exc.jsonObject?.get("exception")?.jsonObject?.get("description")?.str()
                ?: "JS exception"
            CdpEvalResult(null, desc)
        } else {
            val r = resultObj?.get("result")?.jsonObject
            val v = r?.let { stringifyEvalValue(it) }
            CdpEvalResult(v ?: "undefined", null)
        }
    }

    /** Stringify a Runtime.evaluate `result` object safely. `value` may be a primitive OR an
     *  object/array (e.g. `eval document.querySelector('div')` returns an object) — calling
     *  `.jsonPrimitive` on a non-primitive throws. Prefer `description` (CDP populates it for
     *  object results); fall back to the primitive content, or serialize the object/array. */
    private fun stringifyEvalValue(r: JsonObject): String? {
        r["description"]?.str()?.let { return it }
        val value = r["value"] ?: return null
        return when (value) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject, is JsonArray -> json.encodeToString(JsonElement.serializer(), value)
            else -> null
        }
    }

    suspend fun reload() { cdpSend("Page.reload").await() }

    /** Clear the console ring buffer. `MutableStateFlow.value` is atomic — no mutex needed. */
    fun clearConsole() { _console.value = emptyList() }

    /** Clear the network request list. Atomic. */
    fun clearNetwork() { _net.value = emptyList() }

    /** Clear the inline error message (user dismissed the banner). Atomic, no mutex needed. */
    fun clearError() { _error.value = null }

    suspend fun getResponseBody(requestId: String): CdpResponseBody {
        val params = buildJsonObject { put("requestId", requestId) }
        // Timeout: after a page reload the old requestId is gone; DevTools may return an error
        // OR (worse) never respond → without a timeout the modal's spinner would spin forever.
        val resp = try {
            withTimeout(5_000) { cdpSend("Network.getResponseBody", params).await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return CdpResponseBody.Error("timeout (5s) — requestId may be stale after a reload")
        } catch (e: CdpConnectionException) {
            return CdpResponseBody.Error(e.message ?: "connection closed")
        }
        // CDP error response: {"id":N,"error":{"message":"No resource with given identifier found"}}
        resp["error"]?.jsonObject?.get("message")?.str()?.let { return CdpResponseBody.Error(it) }
        val body = resp["result"]?.jsonObject?.get("body")?.str()
        return if (body != null) CdpResponseBody.Body(body) else CdpResponseBody.Error("no body")
    }

    /** Stop the session: cancel the run loop, close the transport, and (one-click mode) remove
     *  the forward. In-flight `evaluate`/`reload`/`getResponseBody` callers (.await()ing deferreds
     *  that only the now-cancelled collector could complete) are released with an exception —
     *  otherwise they'd hang forever. This drain happens BEFORE the run-loop cancel so the
     *  collector doesn't race us for the same deferreds. */
    suspend fun stop() {
        // Drain in-flight callers BEFORE cancelling the collector.
        val stranded = pendingMutex.withLock {
            val vals = pending.values.toList()
            pending.clear()
            vals
        }
        logger.info("[cdp] stop: draining ${stranded.size} stranded, ownsForward=$ownsForward serial=$currentSerial")
        stranded.forEach { it.completeExceptionally(CdpConnectionException("connection closed")) }
        runJob?.cancel()
        runJob = null
        setupComplete = false
        _connectionState.value = CdpConnectionState.DISCONNECTED
        transport.close()
        val serial = currentSerial
        if (ownsForward && serial != null) {
            runCatching { commands.removeForward(serial, ForwardSpec(ForwardEndpointType.TCP, "9222")) }
        }
        ownsForward = false
    }

    private fun JsonElement?.str(): String? = this?.jsonPrimitive?.contentOrNull
}
