package com.adbgui.core.domain

/** Console severity for a CDP console entry. Maps Runtime.consoleAPICalled `type` and Log.entryAdded `level`. */
enum class CdpLevel { LOG, DEBUG, INFO, WARNING, ERROR }

/** One console line from the device WebView (Runtime.consoleAPICalled / Runtime.exceptionThrown / Log.entryAdded).
 *  `id` is a monotonic counter assigned by [com.adbgui.core.device.CdpController] — guarantees a stable LazyColumn
 *  key even when two identical console lines produce the same hashCode (I1: duplicate-line crash). */
data class CdpConsoleEntry(
    val level: CdpLevel,
    val text: String,
    val source: String,        // "console-api" / "exception" / "log-entry" — which CDP event produced it
    val url: String?,
    val lineNumber: Int?,
    val id: Long = 0L,
)

enum class CdpNetState { SENT, RESPONSE, DONE, FAILED }

/** One network request, merged across Network.requestWillBeSent/responseReceived/loadingFinished/Failed by `requestId`. */
data class CdpNetworkRequest(
    val requestId: String,
    val method: String,
    val url: String,
    val state: CdpNetState,
    val status: Int?,
    val mime: String?,
    val error: String?,
)

/** A page target from Target.getTargets. */
data class CdpTarget(val targetId: String, val type: String, val title: String, val url: String)

enum class CdpConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** Result of Runtime.evaluate — either a value or a JS exception (one non-null). */
data class CdpEvalResult(val value: String?, val exception: String?) {
    init {
        require((value != null) xor (exception != null)) { "exactly one of value/exception must be non-null" }
    }
}

/** Parsed CDP event (frames with a `method` field). Responses (frames with an `id` field) are routed by CdpController, not parsed here. */
sealed class CdpEvent {
    data class ConsoleAdd(val entry: CdpConsoleEntry) : CdpEvent()
    data class NetRequest(val req: CdpNetworkRequest) : CdpEvent()
    data class NetResponse(val requestId: String, val status: Int, val mime: String?) : CdpEvent()
    data class NetDone(val requestId: String, val ok: Boolean, val error: String?) : CdpEvent()
}
