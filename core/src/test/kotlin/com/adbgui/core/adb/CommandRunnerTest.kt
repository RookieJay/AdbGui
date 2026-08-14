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
        val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        runner.setBinaryResponse(pngSig)
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val data = cr.screenshot("abc")
        assertTrue(data.contentEquals(pngSig))
    }

    @Test
    fun screenshot_empty_bytes_throws_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setBinaryResponse(ByteArray(0))  // empty → device offline/unauthorized
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val ex = assertFailsWith<AdbCommandException> { cr.screenshot("abc") }
        assert(ex.stderr.contains("no PNG signature"))
    }

    @Test
    fun screenshot_strips_leading_device_shell_banner() = runTest {
        val runner = FakeAdbProcessRunner()
        val banner = "Init wrapper sys mutex successful. Pid:17556\n".toByteArray()
        val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val pngBody = ByteArray(100) { 0x01 }
        runner.setBinaryResponse(banner + pngSig + pngBody)
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val data = cr.screenshot("abc")
        assertTrue(data.copyOfRange(0, 8).contentEquals(pngSig))   // banner stripped
        assertTrue(data.size == pngSig.size + pngBody.size)          // only png remains
    }

    @Test
    fun deviceDetailReport_concatenates_sections_and_is_resilient() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, "[ro.product.model]: [Pixel]", ""))
        runner.whenArgsContains(listOf("wm", "size"), AdbProcessResult(0, "Physical size: 1080x1920", ""))
        runner.whenArgsContains(listOf("meminfo"), AdbProcessResult(0, "MemTotal: 4096", ""))
        runner.whenArgsContains(listOf("battery"), AdbProcessResult(1, "", "dumpsys not found"))  // failing section
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val report = cr.deviceDetailReport("abc")
        assert(report.contains("Serial: abc"))
        assert(report.contains("===== getprop ====="))
        assert(report.contains("Pixel"))
        assert(report.contains("Physical size: 1080x1920"))
        assert(report.contains("[exit 1]"))  // battery failed but report continues
        assert(!report.contains("pm list packages"))  // app list excluded
    }
}
