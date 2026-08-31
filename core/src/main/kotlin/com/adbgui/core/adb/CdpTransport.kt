package com.adbgui.core.adb

import com.adbgui.core.domain.CdpConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Duplex JSON-over-websocket transport for Chrome DevTools Protocol. The `:core` seam — the ktor
 *  actual lives in `:desktop/platform` (KtorCdpTransport); tests use FakeCdpTransport. This is a
 *  NEW seam (AdbStream is one-way line text; CDP needs bidirectional JSON framing). */
interface CdpTransport {
    suspend fun connect(url: String)
    suspend fun send(json: String)
    val incoming: Flow<String>
    val state: StateFlow<CdpConnectionState>
    fun close()
}

class CdpConnectionException(message: String) : Exception(message)
