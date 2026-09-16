package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageParsersTest {
    @Test
    fun parses_f_and_s_format_system_and_user() {
        // `-f`: all packages with APK path; `-s`: system packages only (plain format).
        val fullOut = buildString {
            appendLine("package:/data/app/com.example.app-xxx/base.apk=com.example.app")
            appendLine("package:/system/app/AndroidCalendar/AndroidCalendar.apk=com.android.calendar")
            appendLine("package:/product/app/TVProvider/TVProvider.apk=com.tv.provider")
        }
        val sysOut = "package:com.android.calendar\npackage:com.tv.provider\n"
        val list = PackageListParser.parse(fullOut, sysOut)
        assertEquals(3, list.size)
        assertEquals(true,  list.first { it.name == "com.android.calendar" }.isSystem)
        assertEquals(true,  list.first { it.name == "com.tv.provider" }.isSystem)
        assertEquals(false, list.first { it.name == "com.example.app" }.isSystem)
    }

    @Test
    fun updated_system_app_detected_via_sys_out() {
        // UPDATED_SYSTEM_APP: APK physically in /data/app/ but Android flags it as system.
        // `-s` output is authoritative — without it, path-based detection would wrongly say isSystem=false.
        val fullOut = "package:/data/app/com.iflytek.xiri-1/base.apk=com.iflytek.xiri\n"
        val sysOut  = "package:com.iflytek.xiri\n"
        val list = PackageListParser.parse(fullOut, sysOut)
        assertEquals(1, list.size)
        assertEquals(true, list[0].isSystem, "UPDATED_SYSTEM_APP must be detected via sysOut")
    }

    @Test
    fun parses_plain_format_all_non_system() {
        val out = "package:com.example.app\npackage:com.other.app\n"
        val list = PackageListParser.parse(out)
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
