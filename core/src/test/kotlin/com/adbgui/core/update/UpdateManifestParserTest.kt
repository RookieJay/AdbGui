package com.adbgui.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateManifestParserTest {
    private fun readFixture(name: String): String =
        UpdateManifestParserTest::class.java.getResourceAsStream("/fixtures/update/$name")!!
            .bufferedReader().use { it.readText() }
            // Strip `//` line comments (fixture header convention; not valid JSON).
            .lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test fun parses_valid_manifest() {
        val m = UpdateManifestParser.parse(readFixture("manifest_valid.json"))
        assertEquals("1.1.0", m.version)
        assertEquals("https://example.com/AdbGui-1.1.0.msi", m.url)
        assertEquals(64, m.sha256.length)
        assertEquals(52428800, m.size)
        assertEquals("修复 scrcpy 启动", m.notes)
        assertEquals("1.0.0", m.minAppVersion)
    }

    @Test fun throws_on_missing_sha() {
        val raw = readFixture("manifest_missing_sha.json")
        val ex = assertFailsWith<UpdateManifestParseException> { UpdateManifestParser.parse(raw) }
        assertEquals(raw, ex.raw)
    }

    @Test fun throws_on_bad_sha() {
        assertFailsWith<UpdateManifestParseException> {
            UpdateManifestParser.parse(readFixture("manifest_bad_sha.json"))
        }
    }

    @Test fun throws_on_bad_version() {
        assertFailsWith<UpdateManifestParseException> {
            UpdateManifestParser.parse(readFixture("manifest_bad_version.json"))
        }
    }
}
