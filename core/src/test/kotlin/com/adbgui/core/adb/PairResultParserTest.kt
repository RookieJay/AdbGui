package com.adbgui.core.adb

import com.adbgui.core.domain.PairResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairResultParserTest {
    @Test fun success_paired() {
        val r = PairResultParser.parse("Successfully paired to 192.168.1.50:4321", "", 0)
        assertTrue(r.success)
        assertEquals("Successfully paired to 192.168.1.50:4321", r.message)
    }

    @Test fun failure_invalid_code() {
        val r = PairResultParser.parse("Failment: invalid code", "", 1)
        assertFalse(r.success)
        assertTrue(r.message.contains("invalid code"))
    }

    @Test fun failure_cannot_connect() {
        val r = PairResultParser.parse("", "cannot connect to 10.0.0.1:4321: Connection refused", 1)
        assertFalse(r.success)
        assertTrue(r.message.contains("Connection refused"))
    }

    @Test fun empty_output_defaults_to_exit_message() {
        val r = PairResultParser.parse("", "", 1)
        assertFalse(r.success)
        assertEquals("pair failed (exit 1)", r.message)
    }
}
