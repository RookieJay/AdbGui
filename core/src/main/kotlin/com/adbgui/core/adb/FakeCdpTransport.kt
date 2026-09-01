package com.adbgui.core.adb

import com.adbgui.core.domain.CdpConnectionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Scriptable CdpTransport for tests. enqueue frames into `incoming`; record `send` calls;
 *  script connect success/fail + simulate drops. Mirrors FakeAdbProcessRunner's role. */
class FakeCdpTransport : CdpTransport {
    private val ch = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = ch.receiveAsFlow()
    private val _state = MutableStateFlow(CdpConnectionState.DISCONNECTED)
    override val state: StateFlow<CdpConnectionState> = _state.asStateFlow()

    val sent = mutableListOf<String>()
    var connectUrl: String? = null
        private set
    var connectShouldFail = false

    override suspend fun connect(url: String) {
        connectUrl = url
        if (connectShouldFail) {
            _state.value = CdpConnectionState.FAILED
            throw CdpConnectionException("fake connect fail")
        }
        _state.value = CdpConnectionState.CONNECTED
    }

    override suspend fun send(json: String) { sent.add(json) }

    fun emit(json: String) { ch.trySend(json) }
    /** C1: simulate a peer-drop via state→DISCONNECTED only (do NOT close the channel).
     *  This unifies the Fake/real drop contract: both signal drops via state, not channel close.
     *  The controller's state-observer detects the drop and drives reconnect; the dispatch job's
     *  incoming.collect keeps suspending (channel still open) and resumes when reconnect pumps
     *  new frames. */
    fun simulateDrop() { _state.value = CdpConnectionState.DISCONNECTED }

    override fun close() { _state.value = CdpConnectionState.DISCONNECTED; ch.close() }
}
