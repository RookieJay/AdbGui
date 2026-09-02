package com.adbgui.desktop.platform

import com.adbgui.core.adb.CdpConnectionException
import com.adbgui.core.adb.CdpTransport
import com.adbgui.core.domain.CdpConnectionState
import com.adbgui.core.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Production CdpTransport — ktor-client-cio + ktor-client-websockets actual. Connects ws://localhost:<port>/...,
 *  bridges ktor's session incoming/outgoing to the CdpTransport interface.
 *
 *  `connect()` suspends until the ws handshake completes and throws [CdpConnectionException] on failure
 *  (I-2: the failure propagates to the caller, not swallowed in a fire-and-forget launch). The long-lived
 *  pump (session incoming → [incomingCh], [sessionOut] → session.send) runs in a child of [scope] and keeps
 *  running after `connect()` returns. [incomingCh] is ONE persistent channel for the transport's lifetime
 *  (I-4): the run-loop's `transport.incoming.collect` (started once in [CdpController]) keeps working across
 *  the 2-connect browser→page handoff. Cross-thread vars are `@Volatile` (red-line #3). */
class KtorCdpTransport(
    private val scope: CoroutineScope,
    private val logger: Logger,
) : CdpTransport {
    private val client = HttpClient(CIO) { install(WebSockets) }
    private val _state = MutableStateFlow(CdpConnectionState.DISCONNECTED)
    override val state: StateFlow<CdpConnectionState> = _state.asStateFlow()

    @Volatile private var sessionOut: Channel<String>? = null
    private val incomingCh = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = incomingCh.receiveAsFlow()
    @Volatile private var sessionJob: Job? = null

    override suspend fun connect(url: String) {
        // Tear down any prior session (e.g. browser ws → page ws handoff). Cancellation is clean:
        // the old webSocket block throws CancellationException, which the session re-throws without
        // touching state, so it can't clobber the new connect's CONNECTING/CONNECTED.
        sessionJob?.cancel()

        val out = Channel<String>(Channel.UNLIMITED)
        sessionOut = out
        _state.value = CdpConnectionState.CONNECTING

        // null = handshake ok; non-null = handshake failure (thrown to connect()'s caller).
        val openSignal = CompletableDeferred<Throwable?>()

        sessionJob = scope.launch {
            try {
                // CDP's /devtools/browser endpoint requires an Origin header (node's WebSocket sends
                // one by default; ktor's CIO ws client does NOT — without it the Android WebView
                // DevTools server closes the ws ~3ms after the handshake). Derive Origin host:port from the url.
                val origin = "http://" + url.substringAfter("ws://").substringBefore("/")
                logger.info("[cdp-transport] connecting $url (Origin=$origin)")
                client.webSocket(
                    urlString = url,
                    request = { headers.append(HttpHeaders.Origin, origin) },
                ) {
                    // Handshake done — signal connect() to proceed, then keep pumping.
                    _state.value = CdpConnectionState.CONNECTED
                    openSignal.complete(null)
                    val sendJob = launch { for (msg in out) send(Frame.Text(msg)) }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) incomingCh.send(frame.readText())
                        }
                    } finally { sendJob.cancel() }
                    // Log the peer's close reason (closeReason is a WebSocketSession member, in scope here).
                    val reason = closeReason.await()
                    logger.info("[cdp-transport] ws session ended; closeReason code=${reason?.code} message=${reason?.message}")
                }
                // Session ended (peer closed). Reset only if not FAILED (I-1).
                if (_state.value !== CdpConnectionState.FAILED) {
                    _state.value = CdpConnectionState.DISCONNECTED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = CdpConnectionState.FAILED
                openSignal.complete(e)
                logger.warn("[cdp-transport] ws connect failed: ${e.message}")
            }
        }

        openSignal.await()?.let { throw CdpConnectionException("ws connect failed: ${it.message}") }
    }

    override suspend fun send(json: String) { sessionOut?.send(json) }

    override fun close() {
        sessionJob?.cancel()
        sessionJob = null
        sessionOut?.close()
        sessionOut = null
        // NOTE: do NOT close `incomingCh` here. It is a `val` (I-4: persistent across the
        // browser→page 2-connect handoff within a session). Closing it would permanently kill the
        // transport — the next `connect()`'s session pump would throw "Channel was closed" on
        // `incomingCh.send`, + the controller's `incoming.collect` would complete immediately.
        // The transport must survive `stop()` → re-`connect()` (the VM is app-lifetime; the user
        // re-enters the page + clicks 调试 WebView again). Cancelling `sessionJob` stops the
        // producer; `incoming.collect` just suspends (no frames) until a new session pumps.
        _state.value = CdpConnectionState.DISCONNECTED
    }
}
