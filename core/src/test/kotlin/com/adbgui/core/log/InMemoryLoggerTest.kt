package com.adbgui.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryLoggerTest {
    @Test
    fun records_entries_at_or_above_level() {
        val logger = InMemoryLogger(LogLevel.WARN, clock = { 0L })
        logger.debug("d")
        logger.warn("w")
        logger.error("e", RuntimeException("boom"))
        val entries = logger.entries
        assertEquals(2, entries.size)
        assertEquals("w", entries[0].message)
        assertEquals(LogLevel.ERROR, entries[1].level)
        assertTrue(entries[1].throwable!!.message!!.contains("boom"))
    }
}
