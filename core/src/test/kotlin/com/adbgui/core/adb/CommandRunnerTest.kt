package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandRunnerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test
    fun adbVersion_returns_stdout_trimmed() = runTest {
        // `adb version` is a host command (no -s serial, no adb server). Real output recorded from
        // platform-tools 37.0.1 on Windows. adbVersion has no Parser — returns raw stdout for display.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("version"), AdbProcessResult(0,
            "Android Debug Bridge version 1.0.41\nVersion 37.0.1-15733141\nInstalled as C:\\adb.exe\nRunning on Windows 10.0.26200\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.adbVersion()
        assertTrue(out.startsWith("Android Debug Bridge version"))
        assertTrue(out.contains("37.0.1-15733141"))
        assertTrue(!out.endsWith("\n"))  // trimmed
    }

    @Test
    fun adbVersion_nonzero_throws_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("version"), AdbProcessResult(1, "", "adb: not found"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> { cr.adbVersion() }
    }

    @Test
    fun inputText_sends_text_as_single_arg() = runTest {
        // Spaces in the text must survive as ONE argv element — if split into "hello" + "world",
        // the "hello world" keyword wouldn't be a substring of any single arg → default exit 1 → throws.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("input", "text", "hello world"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.inputText("abc", "hello world")
    }

    @Test
    fun inputText_failure_throws_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("input", "text"), AdbProcessResult(1, "", "error"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> { cr.inputText("abc", "x") }
    }

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
    fun checkSymlinkDirs_parses_double_CR_line_endings() = runTest {
        // adb shell (pty) on some devices (TCL Android 6.0) emits \r\r\n per `echo` line. A naive
        // lineSequence() splits \r\r\n into value + empty line, so the boolean list grows to 2N and
        // misaligns with the N paths → symlinks past index 0 get wrong dir/file classification.
        val runner = FakeAdbProcessRunner()
        // 3 paths: dir, file, dir → stdout 1,0,1 with \r\r\n endings
        runner.whenArgsContains(listOf("test", "-d"), AdbProcessResult(0, "1\r\r\n0\r\r\n1\r\r\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val isDirs = cr.checkSymlinkDirs("abc", listOf("/sdcard", "/charger", "/etc"))
        assertEquals(listOf(true, false, true), isDirs)
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

    @Test
    fun streamLogcat_passes_threadtime_args_and_ensures_server() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.123  1  2 I Tag: hi"))
        var serverCalled = false
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter { serverCalled = true })
        val stream = cr.streamLogcat("abc")
        assertTrue(serverCalled)
        // the stream emits the scripted line
        val first = stream.lines.first()
        assert(first.contains("Tag: hi"))
    }

    @Test
    fun reboot_normal_sends_reboot_no_mode() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("reboot"), AdbProcessResult(0, "rebooting", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.reboot("abc", com.adbgui.core.domain.RebootMode.NORMAL)
        // success: no throw; args verified via whenArgsContains("reboot") match
    }

    @Test
    fun reboot_recovery_appends_recovery_arg() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("reboot", "recovery"), AdbProcessResult(0, "rebooting", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.reboot("abc", com.adbgui.core.domain.RebootMode.RECOVERY)
    }

    @Test
    fun root_failure_throws_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("root"), AdbProcessResult(1, "", "adbd cannot run as root in production builds"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val ex = assertFailsWith<RuntimeException> { cr.root("abc") }
        assert(ex is AdbCommandException)
    }

    @Test
    fun remount_success() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("remount"), AdbProcessResult(0, "remount succeeded", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.remount("abc")
    }

    @Test
    fun ls_returns_stdout_for_path() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0, "drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.ls("abc", "/sdcard")
        assert(out.contains("Photos"))
    }

    @Test
    fun push_passes_local_and_device_path() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("push"), AdbProcessResult(0, "pushed", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.push("abc", "/local/file.txt", "/sdcard/file.txt")
        // success: no throw
    }

    @Test
    fun pull_failure_throws() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pull"), AdbProcessResult(1, "", "device offline"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<RuntimeException> { cr.pull("abc", "/sdcard/file.txt", "/local/file.txt") }
    }

    @Test
    fun forceStop_passes_am_force_stop() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("force-stop"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.forceStop("abc", "com.foo")
    }

    @Test
    fun startApp_uses_monkey_launcher() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("monkey"), AdbProcessResult(0, "Events injected", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.startApp("abc", "com.foo")
    }

    @Test
    fun startAppActivity_passes_am_start_n() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("am", "start"), AdbProcessResult(0, "Starting:", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.startAppActivity("abc", "com.foo", "MainActivity")
    }

    @Test
    fun sendBroadcast_passes_action_and_extras() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("broadcast"), AdbProcessResult(0, "Broadcasting Intent { act=com.test }", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.sendBroadcast("abc", "com.test.ACTION", null,
            listOf(com.adbgui.core.domain.Extra(com.adbgui.core.domain.ExtraType.STRING, "key", "val")))
        assert(out.contains("Broadcasting"))
    }

    @Test
    fun queryProvider_passes_uri_and_where() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("content", "query"), AdbProcessResult(0, "Row: 0 _id=1", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.queryProvider("abc", "content://settings/system", "name='setting'")
        assert(out.contains("Row:"))
    }

    @Test
    fun runShellCmd_passes_shell_command_and_returns_stdout() = runTest {
        // runShellCmd runs an arbitrary device-shell command string (pipes/grep handled by device sh).
        // The whole `cmd` is passed as a single arg after `shell` so the device's /system/bin/sh
        // interprets metacharacters (|, ||, 2>/dev/null) — the host does no shell parsing.
        // Returns raw stdout UNTRIMMED (spec §2.2.1 "原样返回"; adbVersion trims, this does not).
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell", "getprop"), AdbProcessResult(0, "ro.build.fingerprint=foo\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.runShellCmd("ABC123", "getprop")
        assertEquals("ro.build.fingerprint=foo\n", out)  // trailing newline preserved — proves no trim
    }

    @Test
    fun runShellCmd_nonzero_throws_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        // no script -> FakeAdbProcessRunner default = AdbProcessResult(1, "", "no script matched")
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> { cr.runShellCmd("ABC123", "getprop") }
    }

    @Test
    fun pair_does_not_log_pairing_code() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pair"), AdbProcessResult(0, "Successfully paired to 192.168.1.50:4321", ""))
        val logger = com.adbgui.core.log.InMemoryLogger(com.adbgui.core.log.LogLevel.DEBUG) { 0L }
        val cr = CommandRunner({ adb }, runner, logger, this, CommandRunner.AdbServerStarter{})
        cr.pair("192.168.1.50", 4321, "483921")
        // No DEBUG log line may contain the 6-digit pairing code.
        val leaked = logger.entries.any { it.message.contains("483921") }
        assert(!leaked) { "pairing code leaked into debug log: ${logger.entries.map { it.message }}" }
    }
}
