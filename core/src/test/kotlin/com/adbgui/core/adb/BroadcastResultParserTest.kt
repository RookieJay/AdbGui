package com.adbgui.core.adb

import com.adbgui.core.domain.BroadcastResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BroadcastResultParserTest {
    private val success = "Broadcasting Intent { act=com.test.ACTION flg=0x400000 }"
    private val failure = "Error: Intent not found"

    @Test fun parses_success() {
        val r = BroadcastResultParser.parse(success, "", 0)
        assertTrue(r.success)
        assertTrue(r.message.contains("com.test.ACTION"))
    }

    @Test fun parses_failure_exit_nonzero() {
        val r = BroadcastResultParser.parse("", failure, 1)
        assertEquals(false, r.success)
        assertTrue(r.message.contains("Intent not found"))
    }

    @Test fun empty_output_defaults_to_failure() {
        val r = BroadcastResultParser.parse("", "", 1)
        assertEquals(false, r.success)
    }
}
