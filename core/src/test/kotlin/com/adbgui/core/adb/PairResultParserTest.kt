package com.adbgui.core.adb

import com.adbgui.core.domain.PairResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairResultParserTest {
    // --- Real recorded fixtures (CLAUDE.md §4: fixtures must be real recordings) ---

    private fun loadFixture(name: String): String {
        val raw = PairResultParserTest::class.java.getResourceAsStream("/fixtures/$name")!!
            .bufferedReader().readText()
        // Drop leading '# ...' comment lines so only adb output is parsed.
        return raw.lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n").trim()
    }

    @Test fun parses_success_from_real_fixture() {
        // Recorded from OnePlus 12 / Android 16, 2026-08-21.
        // adb pair output: "Enter pairing code: Successfully paired to 10.0.5.221:43849 [guid=adb-af199bb4-rY5hPZ]"
        val out = loadFixture("pair_success.txt")
        val r = PairResultParser.parse(out, "", 0)
        assertTrue(r.success, "expected success from real fixture, got: $r")
        assertTrue(r.message.contains("Successfully paired"))
        assertTrue(r.message.contains("10.0.5.221:43849"))
        // The [guid=...] suffix is a real adb addition since ~adb 34; parser must not choke on it.
        assertTrue(r.message.contains("[guid="))
    }

    @Test fun parses_protocol_fault_failure_from_real_fixture() {
        // Recorded from OnePlus 12 / Android 16, 2026-08-21.
        // adb pair to a non-existent/closed port: "error: protocol fault (couldn't read status message): No error"
        val out = loadFixture("pair_failure_protocol.txt")
        val r = PairResultParser.parse(out, "", 1)
        assertFalse(r.success)
        assertTrue(r.message.contains("protocol fault"))
    }

    // --- Supplemental literal cases (no real fixture yet; cover parser branches) ---

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
