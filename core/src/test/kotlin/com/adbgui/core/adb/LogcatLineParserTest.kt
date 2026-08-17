package com.adbgui.core.adb

import com.adbgui.core.domain.LogcatLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogcatLineParserTest {
    private val line = "08-17 10:23:45.123  1234  5678 I ActivityManager: Display changed"

    @Test fun parses_threadtime_fields() {
        val p = LogcatLineParser.parse(line)
        assertEquals("08-17 10:23:45.123", p?.timestamp)
        assertEquals(1234, p?.pid)
        assertEquals(5678, p?.tid)
        assertEquals(LogcatLevel.I, p?.level)
        assertEquals("ActivityManager", p?.tag)
        assertEquals("Display changed", p?.message)
        assertEquals(line, p?.raw)
    }

    @Test fun parses_each_level() {
        assertEquals(LogcatLevel.W, LogcatLineParser.parse("08-17 10:23:45.200  1234  5679 W System.err: x")?.level)
        assertEquals(LogcatLevel.E, LogcatLineParser.parse("08-17 10:23:45.300  2000  2001 E AndroidRuntime: y")?.level)
        assertEquals(LogcatLevel.D, LogcatLineParser.parse("08-17 10:23:45.400  1234  5680 D TestTag: z")?.level)
        assertEquals(LogcatLevel.V, LogcatLineParser.parse("08-17 10:23:45.500  1234  5681 V lowlevel: w")?.level)
    }

    @Test fun returns_null_for_blank_and_separator_lines() {
        assertNull(LogcatLineParser.parse(""))
        assertNull(LogcatLineParser.parse("   "))
        assertNull(LogcatLineParser.parse("--------- beginning of main"))
    }
}
