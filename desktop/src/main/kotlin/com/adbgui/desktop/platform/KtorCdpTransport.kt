package com.adbgui.desktop.platform

import com.adbgui.core.adb.CdpConnectionException
import com.adbgui.core.adb.CdpTransport
import com.adbgui.core.domain.CdpConnectionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
 *  bridges ktor's session incoming/outgoing to the CdpTransport interface. */
class KtorCdpTransport(private val scope: CoroutineScope) : CdpTransport {
    private val client = HttpClient(CIO) { install(WebSockets) }
    private val _state = MutableStateFlow(CdpConnectionState.DISCONNECTED)
    override val state: StateFlow<CdpConnectionState> = _state.asStateFlow()
    private var sessionOut: Channel<String>? = null
    private var incomingCh = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = incomingCh.receiveAsFlow()
    private var sessionJob: Job? = null
    @Volatile private var currentUrl: String? = null

    override suspend fun connect(url: String) {
        currentUrl = url
        _state.value = CdpConnectionState.CONNECTING
        sessionJob?.cancel()
        incomingCh.close(); incomingCh = Channel(Channel.UNLIMITED)
        sessionOut = Channel(Channel.UNLIMITED)
        sessionJob = scope.launch {
            try {
                client.webSocket(urlString = url) {
                    _state.value = CdpConnectionState.CONNECTED
                    val outgoing = sessionOut!!
                    // Pump outgoing → session.send
                    val sendJob = launch { for (msg in outgoing) send(Frame.Text(msg)) }
                    // Pump session.incoming → our incomingCh
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) incomingCh.send(frame.readText())
                        }
                    } finally { sendJob.cancel() }
                }
            } catch (e: Throwable) {
                _state.value = CdpConnectionState.FAILED
                throw CdpConnectionException("ws connect failed: ${e.message}")
            } finally {
                _state.value = CdpConnectionState.DISCONNECTED
            }
        }
    }

    override suspend fun send(json: String) { sessionOut?.send(json) }

    override fun close() {
        sessionJob?.cancel(); sessionJob = null
        incomingCh.close()
        _state.value = CdpConnectionState.DISCONNECTED
    }
}
