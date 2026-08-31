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
import com.adbgui.core.domain.CdpTarget
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardSpec
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _console = MutableStateFlow<List<CdpConsoleEntry>>(emptyList())
    val consoleEntries: StateFlow<List<CdpConsoleEntry>> = _console.asStateFlow()
    private val _net = MutableStateFlow<List<CdpNetworkRequest>>(emptyList())
    val networkRequests: StateFlow<List<CdpNetworkRequest>> = _net.asStateFlow()
    private val _targets = MutableStateFlow<List<CdpTarget>>(emptyList())
    val targets: StateFlow<List<CdpTarget>> = _targets.asStateFlow()
    val state: StateFlow<CdpConnectionState> = transport.state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // pending request id → CompletableDeferred for the matching response. Guarded by pendingMutex.
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
    private val pendingMutex = Mutex()
    private val nextId = AtomicInteger(1)

    private var runJob: Job? = null
    @Volatile private var ownsForward = false        // one-click mode → stop() removes the forward
    @Volatile private var currentSerial: String? = null
    @Volatile private var currentPageId: String? = null
    @Volatile private var currentPort: Int = 9222

    /** CDP domains to enable on connect + on reconnect. Enabling is what makes the WebView
     *  emit console/network events; re-enabling after a drop is what un-sticks event flow. */
    private val domainEnables = listOf("Runtime.enable", "Page.enable", "Network.enable", "Log.enable")

    /** One-click: probe the WebView socket → forward tcp:9222 → connect browser ws → pick page →
     *  enable domains. Throws [CdpConnectionException] with an actionable message if no socket. */
    suspend fun start(serial: String) {
        currentSerial = serial
        currentPort = 9222
        val socket = commands.webviewSocket(serial)
            ?: throw CdpConnectionException("无 webview socket — 目标应用需在前台运行且含 WebView")
        commands.forward(serial,
            ForwardSpec(ForwardEndpointType.TCP, "9222"),
            ForwardSpec(ForwardEndpointType.LOCALABSTRACT, socket))
        ownsForward = true
        connectAndRun(portOverride = null)
    }

    /** Manual: skip the forward (user set it up themselves), connect to
     *  ws://localhost:<port>/devtools/browser directly. */
    suspend fun connectManual(port: Int) {
        ownsForward = false
        currentSerial = null
        currentPort = port
        connectAndRun(portOverride = port)
    }

    private fun connectAndRun(portOverride: Int?) {
        runJob?.cancel()
        val port = portOverride ?: currentPort
        currentPort = port
        runJob = scope.launch {
            try {
                transport.connect("ws://localhost:$port/devtools/browser")
                // Start the inbound-frame collector on a sibling child so setup-command awaits
                // (Target.getTargets / domain enables) do NOT block event dispatch — events flow
                // even while we're suspended waiting for a setup response.
                launch { runLoop(port) }
                // CDP responses wrap the payload under "result": {"id":N,"result":{...}}.
                // Unwrap before reading targetInfos (was reading at top level → always null).
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
                val page = pages.firstOrNull()
                    ?: throw CdpConnectionException("没有 page target（应用没在前台？）")
                currentPageId = page.targetId
                transport.connect("ws://localhost:$port/devtools/page/${page.targetId}")
                domainEnables.forEach { cdpSend(it).await() }
            } catch (e: CdpConnectionException) {
                _error.value = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _error.value = t.message
                logger.warn("[cdp] connectAndRun error: ${t.message}")
            }
        }
    }

    /** Reconnect loop mirroring LogcatController: collect inbound frames; on stream end/error
     *  back off (1s→…→30s cap) and reconnect. 3 consecutive failures degrade to FAILED. */
    private suspend fun runLoop(port: Int) {
        var backoff = 1000L
        var failures = 0
        while (true) {
            try {
                transport.incoming.collect { frame -> handleFrame(frame) }
                logger.warn("[cdp] incoming stream ended")
                failures++
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logger.warn("[cdp] stream error: ${t.message}")
                _error.value = t.message
                failures++
            }
            if (failures >= 3) failures = 0  // keep retrying after FAILED-grade backoff
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
            val page = currentPageId ?: ""
            // Reconnect the page ws; on success re-enable domains so events flow again
            // (M-2: without this, a drop leaves the session alive but silent).
            runCatching { transport.connect("ws://localhost:$port/devtools/page/$page") }
                .onSuccess { domainEnables.forEach { runCatching { cdpSend(it) } } }
        }
    }

    private suspend fun handleFrame(frame: String) {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: run {
            logger.debug("[cdp] malformed frame (not JSON object): ${frame.take(120)}")
            return
        }
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (id != null) {
            // Response — complete the pending request (correlation by id).
            val completer = pendingMutex.withLock { pending.remove(id) }
            completer?.complete(obj)
            return
        }
        val method = obj["method"]?.str() ?: return
        val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
        val event = CdpEventParser.parseEvent(method, params)
        if (event == null) {
            logger.debug("[cdp] unknown CDP method: $method")
            return
        }
        applyEvent(event)
    }

    private suspend fun applyEvent(e: CdpEvent) {
        pendingMutex.withLock {
            when (e) {
                is CdpEvent.ConsoleAdd ->
                    _console.value = (_console.value + e.entry).takeLast(ringCap)
                is CdpEvent.NetRequest ->
                    _net.value = _net.value + e.req
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

    /** Clear the inline error message (user dismissed the banner). Atomic, no mutex needed. */
    fun clearError() { _error.value = null }

    suspend fun getResponseBody(requestId: String): String? {
        val params = buildJsonObject { put("requestId", requestId) }
        val resp = cdpSend("Network.getResponseBody", params).await()
        // CDP wraps the body under "result": {"id":N,"result":{"body":"…","base64Encoded":false}}.
        return resp["result"]?.jsonObject?.get("body")?.str()
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
        stranded.forEach { it.completeExceptionally(CdpConnectionException("connection closed")) }
        runJob?.cancel()
        runJob = null
        transport.close()
        val serial = currentSerial
        if (ownsForward && serial != null) {
            runCatching { commands.removeForward(serial, ForwardSpec(ForwardEndpointType.TCP, "9222")) }
        }
        ownsForward = false
    }

    private fun JsonElement?.str(): String? = this?.jsonPrimitive?.contentOrNull
}
