package com.adbgui.core.adb

import com.adbgui.core.domain.CdpConsoleEntry
import com.adbgui.core.domain.CdpEvent
import com.adbgui.core.domain.CdpLevel
import com.adbgui.core.domain.CdpNetState
import com.adbgui.core.domain.CdpNetworkRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure parser for CDP event frames (those with a `method` field). Response frames (with an `id`)
 *  are routed by CdpController, not here. Returns null for unknown methods or malformed payloads —
 *  never throws (CdpController logs unknown methods at DEBUG per the "don't silently swallow" rule). */
object CdpEventParser {

    fun parseEvent(method: String, params: JsonObject): CdpEvent? {
        return when (method) {
            "Runtime.consoleAPICalled" -> parseConsoleApi(params)
            "Runtime.exceptionThrown" -> parseException(params)
            "Log.entryAdded" -> parseLogEntry(params)
            "Network.requestWillBeSent" -> parseNetRequest(params)
            "Network.responseReceived" -> parseNetResponse(params)
            "Network.loadingFinished" -> CdpEvent.NetDone(reqId(params), true, null)
            "Network.loadingFailed" -> CdpEvent.NetDone(reqId(params), false, params["errorText"]?.str())
            else -> null
        }
    }

    private fun parseConsoleApi(p: JsonObject): CdpEvent.ConsoleAdd? {
        val type = p["type"]?.str() ?: return null
        val args = p["args"]?.jsonArray ?: return CdpEvent.ConsoleAdd(
            CdpConsoleEntry(level = mapLevel(type), text = "", source = "console-api", url = null, lineNumber = null))
        val text = args.joinToString(" ") { argText(it) }
        return CdpEvent.ConsoleAdd(CdpConsoleEntry(mapLevel(type), text, "console-api", null, null))
    }

    private fun parseException(p: JsonObject): CdpEvent.ConsoleAdd? {
        val det = p["exceptionDetails"]?.jsonObject ?: return null
        val text = (det["text"]?.str().orEmpty() + " " +
            (det["exception"]?.jsonObject?.get("description")?.str().orEmpty())).trim()
        return CdpEvent.ConsoleAdd(CdpConsoleEntry(CdpLevel.ERROR, text, "exception",
            det["url"]?.str(), det["lineNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()))
    }

    private fun parseLogEntry(p: JsonObject): CdpEvent.ConsoleAdd? {
        val e = p["entry"]?.jsonObject ?: return null
        val level = mapLevel(e["level"]?.str() ?: "log")
        val text = e["text"]?.str() ?: ""
        return CdpEvent.ConsoleAdd(CdpConsoleEntry(level, text, "log-entry",
            e["url"]?.str(), e["lineNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()))
    }

    private fun parseNetRequest(p: JsonObject): CdpEvent.NetRequest? {
        val req = p["request"]?.jsonObject ?: return null
        val id = p["requestId"]?.str() ?: return null
        return CdpEvent.NetRequest(CdpNetworkRequest(
            requestId = id,
            method = req["method"]?.str() ?: "",
            url = req["url"]?.str() ?: "",
            state = CdpNetState.SENT, status = null, mime = null, error = null,
        ))
    }

    private fun parseNetResponse(p: JsonObject): CdpEvent.NetResponse? {
        val resp = p["response"]?.jsonObject ?: return null
        val id = p["requestId"]?.str() ?: return null
        val status = resp["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val mime = resp["mimeType"]?.str()
        return CdpEvent.NetResponse(id, status, mime)
    }

    private fun mapLevel(s: String): CdpLevel = when (s.lowercase()) {
        "log" -> CdpLevel.LOG
        "debug", "verbose" -> CdpLevel.DEBUG
        "info" -> CdpLevel.INFO
        "warning", "warn" -> CdpLevel.WARNING
        "error" -> CdpLevel.ERROR
        else -> CdpLevel.LOG
    }

    private fun argText(e: JsonElement?): String = when (e) {
        is JsonObject -> e["value"]?.str() ?: e["description"]?.str() ?: e["unserializableValue"]?.str() ?: "?"
        is JsonPrimitive -> e.contentOrNull ?: "?"
        else -> "?"
    }

    private fun JsonElement?.str(): String? = this?.jsonPrimitive?.contentOrNull
    private fun reqId(p: JsonObject): String = p["requestId"]?.str() ?: ""
}
