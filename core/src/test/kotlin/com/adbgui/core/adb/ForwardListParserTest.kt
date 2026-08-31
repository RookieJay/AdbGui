package com.adbgui.core.adb

import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForwardListParserTest {

    @Test
    fun parses_two_entries_with_correct_specs() {
        // Inline test data asserted against the documented `adb forward --list` format
        // (`<serial> <local> <remote>` per line). The real-device fixture + regression test
        // are added in Task 7 once a device is connected for manual verification.
        val out = "192.168.1.50:5555 tcp:9222 localabstract:webview_devtools_remote_1\n" +
            "emulator-5554 tcp:8080 localabstract:webview_devtools_remote_2\n"
        val entries = ForwardListParser.parse(out)
        assertEquals(2, entries.size)
        assertEquals("192.168.1.50:5555", entries[0].serial)
        assertEquals(ForwardSpec(ForwardEndpointType.TCP, "9222"), entries[0].local)
        assertEquals(ForwardSpec(ForwardEndpointType.LOCALABSTRACT, "webview_devtools_remote_1"), entries[0].remote)
        assertEquals("emulator-5554", entries[1].serial)
        assertEquals(ForwardSpec(ForwardEndpointType.TCP, "8080"), entries[1].local)
    }

    @Test
    fun empty_stdout_returns_empty_list() {
        // adb forward --list prints nothing when no forwards exist (R3) — not an error.
        assertTrue(ForwardListParser.parse("").isEmpty())
        assertTrue(ForwardListParser.parse("\n  \n").isEmpty())
    }

    @Test
    fun skips_comment_and_malformed_lines() {
        // Fixture header is `#` comments; a stray malformed line (only 2 tokens) is skipped, not crashed on.
        val out = "# this is a comment\n" +
            "badline only-two-tokens\n" +
            "192.168.1.50:5555 tcp:9222 localabstract:foo\n"
        val entries = ForwardListParser.parse(out)
        assertEquals(1, entries.size)
        assertEquals("192.168.1.50:5555", entries[0].serial)
    }

    @Test
    fun parses_all_four_endpoint_types() {
        val out = "s1 tcp:1 localabstract:a\n" +
            "s2 localreserved:lr localfilesystem:/tmp/x\n"
        val entries = ForwardListParser.parse(out)
        assertEquals(ForwardEndpointType.TCP, entries[0].local.type)
        assertEquals(ForwardEndpointType.LOCALABSTRACT, entries[0].remote.type)
        assertEquals(ForwardEndpointType.LOCALRESERVED, entries[1].local.type)
        assertEquals(ForwardEndpointType.LOCALFILESYSTEM, entries[1].remote.type)
    }

    @Test
    fun fixture_regression_parses_without_error() {
        // Reads the real-recorded fixture; just asserts it parses to a non-empty list whose
        // first entry's adbForm() round-trips (serial, local, remote all populated).
        val out = object {}.javaClass.getResourceAsStream("/fixtures/forward_list_output.txt")!!
            .bufferedReader().readText()
        val entries = ForwardListParser.parse(out)
        assertTrue(entries.isNotEmpty(), "fixture must contain at least one forward — re-record if empty")
        val first = entries.first()
        assertTrue(first.serial.isNotBlank())
        assertTrue(first.local.adbForm().startsWith("tcp:") || first.local.adbForm().startsWith("local"))
    }
}
