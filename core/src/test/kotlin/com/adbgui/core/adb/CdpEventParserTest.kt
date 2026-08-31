package com.adbgui.core.adb

import com.adbgui.core.domain.CdpEvent
import com.adbgui.core.domain.CdpLevel
import com.adbgui.core.domain.CdpNetState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdpEventParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun frame(method: String, paramsJson: String): Pair<String, JsonObject> {
        val p = json.parseToJsonElement(paramsJson).jsonObject
        return method to p
    }

    @Test
    fun console_apicalled_adds_console_entry() {
        val (m, p) = frame("Runtime.consoleAPICalled",
            """{"type":"log","args":[{"value":"hello"}]}""")
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e is CdpEvent.ConsoleAdd)
        assertEquals(CdpLevel.LOG, e.entry.level)
        assertEquals("hello", e.entry.text)
    }

    @Test
    fun console_apicalled_error_level_mapped() {
        val (m, p) = frame("Runtime.consoleAPICalled",
            """{"type":"error","args":[{"value":"boom"}]}""")
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e is CdpEvent.ConsoleAdd)
        assertEquals(CdpLevel.ERROR, e.entry.level)
    }

    @Test
    fun exception_thrown_adds_error_entry() {
        val (m, p) = frame("Runtime.exceptionThrown",
            """{"exceptionDetails":{"text":"Uncaught","exception":{"description":"TypeError: x is not a function"}}}""")
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e is CdpEvent.ConsoleAdd)
        assertEquals(CdpLevel.ERROR, e.entry.level)
        assertTrue(e.entry.text.contains("TypeError"))
    }

    @Test
    fun log_entry_added_adds_entry() {
        val (m, p) = frame("Log.entryAdded",
            """{"entry":{"level":"warning","source":"network","text":"CORS blocked","url":"http://x","lineNumber":1}}""")
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e is CdpEvent.ConsoleAdd)
        assertEquals(CdpLevel.WARNING, e.entry.level)
        assertTrue(e.entry.text.contains("CORS"))
    }

    @Test
    fun network_request_will_be_sent_yields_net_request() {
        val (m, p) = frame("Network.requestWillBeSent",
            """{"requestId":"1","request":{"method":"GET","url":"http://a/"}}""")
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e is CdpEvent.NetRequest)
        assertEquals("1", e.req.requestId)
        assertEquals("GET", e.req.method)
        assertEquals("http://a/", e.req.url)
        assertEquals(CdpNetState.SENT, e.req.state)
    }

    @Test
    fun network_response_received_yields_net_response() {
        val (m, p) = frame("Network.responseReceived",
            """{"requestId":"1","response":{"status":200,"mimeType":"text/html"}}""")
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e is CdpEvent.NetResponse)
        assertEquals("1", e.requestId)
        assertEquals(200, e.status)
        assertEquals("text/html", e.mime)
    }

    @Test
    fun network_loading_finished_yields_net_done_ok() {
        val (m, p) = frame("Network.loadingFinished", """{"requestId":"1"}""")
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e is CdpEvent.NetDone)
        assertTrue(e.ok)
        assertNull(e.error)
    }

    @Test
    fun network_loading_failed_yields_net_done_err() {
        val (m, p) = frame("Network.loadingFailed", """{"requestId":"1","errorText":"net::ERR_FAILED"}""")
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e is CdpEvent.NetDone)
        assertEquals("net::ERR_FAILED", e.error)
    }

    @Test
    fun unknown_method_returns_null() {
        val (m, p) = frame("Something.future", """{"foo":"bar"}""")
        assertNull(CdpEventParser.parseEvent(m, p))
    }

    @Test
    fun malformed_params_returns_null_not_crash() {
        // params missing required fields for a known method → null (skip), not exception
        val (m, p) = frame("Runtime.consoleAPICalled", """{"type":"log"}""")
        // args missing → text falls back; this should still produce a ConsoleAdd with empty text, OR null.
        // Either is acceptable per R4's "don't crash" — pin the chosen behavior here:
        val e = CdpEventParser.parseEvent(m, p)
        assertTrue(e == null || (e is CdpEvent.ConsoleAdd && e.entry.text.isEmpty()),
            "missing args must not crash; got $e")
    }

    @Test
    fun fixture_regression_real_console_apicalled_parses() {
        val raw = object {}.javaClass.getResourceAsStream("/fixtures/cdp/console_apicalled.ndjson")!!
            .bufferedReader().readText()
        val parsed = raw.lineSequence().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }
            .mapNotNull { CdpEventParser.parseEvent(it["method"]!!.toString().trim('"'), it["params"]!!.jsonObject) }
            .toList()
        assertTrue(parsed.isNotEmpty(), "fixture must yield at least one ConsoleAdd — re-record if empty")
        assertTrue(parsed.all { it is CdpEvent.ConsoleAdd })
    }
}
