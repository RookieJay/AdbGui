package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandRunnerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test
    fun connect_success_returns_parsed_result() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 192.168.1.50:5555", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val r = cr.connect("192.168.1.50", 5555)
        assertTrue(r.success)
    }

    @Test
    fun listPackages_parses_output() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\npackage:com.bar\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val list = cr.listPackages("abc")
        assertEquals(2, list.size)
    }

    @Test
    fun install_failure_throws_with_raw_stderr() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install"), AdbProcessResult(1, "Failure [INSTALL_FAILED_OLDER_SDK]", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val ex = assertFailsWith<RuntimeException> { cr.install("abc", "x.apk", reinstall = true) }
        // AdbCommandException is a RuntimeException; check message carries context
        assert(ex.message!!.contains("install"))
    }

    @Test
    fun screenshot_returns_png_bytes() = runTest {
        val runner = FakeAdbProcessRunner()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        runner.setBinaryResponse(png)
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val data = cr.screenshot("abc")
        assertTrue(data.contentEquals(png))
    }

    @Test
    fun screenshot_empty_bytes_throws_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setBinaryResponse(ByteArray(0))  // empty → device offline/unauthorized
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val ex = assertFailsWith<AdbCommandException> { cr.screenshot("abc") }
        assert(ex.stderr.contains("no image data"))
    }
}
