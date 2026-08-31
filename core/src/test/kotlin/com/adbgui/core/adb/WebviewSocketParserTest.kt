package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebviewSocketParserTest {
    @Test
    fun extracts_webview_devtools_remote_socket_from_real_fixture() {
        // Real recording from a Hisense VIDAA_TV (Android 9, SDK 28) — see file header for provenance.
        val raw = object {}.javaClass.getResourceAsStream("/fixtures/webview_unix_output.txt")!!
            .bufferedReader().readText()
        val socket = WebviewSocketParser.parse(raw)
        assertEquals("webview_devtools_remote_15074", socket)
    }

    @Test
    fun returns_null_when_no_webview_socket() {
        assertNull(WebviewSocketParser.parse("no webview here\njust regular sockets\n"))
    }

    @Test
    fun ignores_at_prefix_of_abstract_socket() {
        // /proc/net/unix renders abstract sockets with a leading '@'; adb forward localabstract
        // wants the bare name (no '@'), so the parser must strip it.
        assertEquals("webview_devtools_remote_42",
            WebviewSocketParser.parse("@webview_devtools_remote_42"))
    }
}
