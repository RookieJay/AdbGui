package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageParsersTest {
    @Test
    fun parses_third_party_package_list() {
        val out = "package:com.example.app\npackage:com.other.app\n"
        val list = PackageListParser.parse(out, thirdPartyOnly = true)
        assertEquals(2, list.size)
        assertEquals("com.example.app", list[0].name)
        assertEquals(false, list[0].isSystem)
    }

    @Test
    fun install_success() {
        val r = InstallResultParser.parse("Performing Streamed Install\nSuccess", "", 0)
        assertTrue(r.success)
    }

    @Test
    fun install_failure_extracts_code() {
        val r = InstallResultParser.parse("Failure [INSTALL_FAILED_OLDER_SDK]", "", 1)
        assertFalse(r.success)
        assertEquals("INSTALL_FAILED_OLDER_SDK", r.code)
    }
}
