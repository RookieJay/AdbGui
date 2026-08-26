package com.adbgui.core.adb

import com.adbgui.core.domain.ConnectFailureReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectResultParserTest {
    @Test
    fun success_connected() {
        val r = ConnectResultParser.parse("connected to 192.168.1.50:5555", "", 0)
        assertTrue(r.success)
        assertEquals("192.168.1.50:5555", r.serial)
        assertNull(r.reason)
    }

    @Test
    fun success_already_connected() {
        val r = ConnectResultParser.parse("already connected to 192.168.1.50:5555", "", 0)
        assertTrue(r.success)
        assertNull(r.reason)
    }

    @Test
    fun failure_connection_refused_classified_port_stale() {
        // Reboot / re-enable of wireless debugging randomizes the connect port; the stored
        // ip:port is now stale → "Connection refused" (host up, nothing listening).
        val r = ConnectResultParser.parse("failed to connect to 192.168.1.50:5555", "cannot connect to 192.168.1.50:5555: Connection refused", 1)
        assertFalse(r.success)
        assertEquals(ConnectFailureReason.PORT_STALE, r.reason)
        assertTrue(r.message.contains("Connection refused"))
    }

    @Test
    fun failure_timeout_classified_unreachable() {
        val r = ConnectResultParser.parse("", "cannot connect to 10.0.0.1:5555: ADB server didn't ACK: timed out", 1)
        assertFalse(r.success)
        assertEquals(ConnectFailureReason.UNREACHABLE, r.reason)
    }

    @Test
    fun failure_network_unreachable_classified_unreachable() {
        val r = ConnectResultParser.parse("", "cannot connect to 10.0.0.1:5555: Network is unreachable", 1)
        assertFalse(r.success)
        assertEquals(ConnectFailureReason.UNREACHABLE, r.reason)
    }

    @Test
    fun failure_other_reason_preserves_message() {
        val r = ConnectResultParser.parse("", "cannot connect to 192.168.1.50:5555: protocol fault", 1)
        assertFalse(r.success)
        assertEquals(ConnectFailureReason.OTHER, r.reason)
        assertTrue(r.message.contains("protocol fault"))
    }
}
