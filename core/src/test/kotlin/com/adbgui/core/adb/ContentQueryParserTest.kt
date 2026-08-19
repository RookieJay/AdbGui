package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentQueryParserTest {
    private val out = """
        Row: 0 _id=1, name=foo, value=bar
        Row: 1 _id=2, name=baz, value=qux
    """.trimIndent()

    @Test fun parses_rows_into_maps() {
        val rows = ContentQueryParser.parse(out)
        assertEquals(2, rows.size)
        assertEquals("1", rows[0]["_id"])
        assertEquals("foo", rows[0]["name"])
        assertEquals("bar", rows[0]["value"])
        assertEquals("2", rows[1]["_id"])
    }

    @Test fun empty_output_returns_empty_list() {
        assertEquals(0, ContentQueryParser.parse("").size)
    }
}
