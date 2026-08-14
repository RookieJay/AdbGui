package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectResultParserTest {
    @Test
    fun success_connected() {
        val r = ConnectResultParser.parse("connected to 192.168.1.50:5555", "", 0)
        assertTrue(r.success)
        assertEquals("192.168.1.50:5555", r.serial)
    }

    @Test
    fun success_already_connected() {
        val r = ConnectResultParser.parse("already connected to 192.168.1.50:5555", "", 0)
        assertTrue(r.success)
    }

    @Test
    fun failure_connection_refused() {
        val r = ConnectResultParser.parse("failed to connect to 192.168.1.50:5555", "cannot connect to 192.168.1.50:5555: Connection refused", 1)
        assertFalse(r.success)
        assert(r.message.contains("Connection refused"))
    }

    @Test
    fun failure_timeout() {
        val r = ConnectResultParser.parse("", "cannot connect to 10.0.0.1:5555: ADB server didn't ACK: timed out", 1)
        assertFalse(r.success)
    }
}
