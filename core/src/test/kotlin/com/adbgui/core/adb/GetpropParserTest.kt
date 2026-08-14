package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals

class GetpropParserTest {
    private val out = """
        [ro.product.model]: [Pixel 6]
        [ro.build.version.release]: [13]
        [ro.build.version.sdk]: [33]
        [ro.product.cpu.abi]: [arm64-v8a]
    """.trimIndent()

    @Test
    fun parses_known_props() {
        val p = GetpropParser.parse(out, serial = "abc")
        assertEquals("Pixel 6", p.model)
        assertEquals("13", p.androidVersion)
        assertEquals(33, p.sdkInt)
        assertEquals("arm64-v8a", p.abi)
        assertEquals("abc", p.serial)
        assertEquals("unknown", p.resolution)
    }

    @Test
    fun missing_sdk_defaults_to_zero() {
        val p = GetpropParser.parse("[ro.product.model]: [X]", "abc")
        assertEquals(0, p.sdkInt)
    }
}
