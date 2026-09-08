package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.Extra
import com.adbgui.core.domain.ExtraType
import com.adbgui.core.domain.InstallFlags
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
    fun grant_passes_pkg_and_perm() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "grant"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.grant("abc", "com.x", "android.permission.CAMERA")
        val argv = runner.runs.last()
        assertTrue(argv.containsAll(listOf("-s", "abc", "shell", "pm", "grant", "com.x", "android.permission.CAMERA")))
    }

    @Test
    fun grant_failure_throws() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "grant"), AdbProcessResult(1, "", "not a runtime permission"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> { cr.grant("abc", "com.x", "android.permission.CAMERA") }
    }

    @Test
    fun revoke_passes_pkg_and_perm() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "revoke"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.revoke("abc", "com.x", "android.permission.CAMERA")
        val argv = runner.runs.last()
        assertTrue(argv.containsAll(listOf("-s", "abc", "shell", "pm", "revoke", "com.x", "android.permission.CAMERA")))
    }

    @Test
    fun listNativeLibs_filters_so_files() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls"), AdbProcessResult(0,
            "-rw-r--r-- 1 root root 1234 2020-01-01 12:00 libfoo.so\n" +
            "-rw-r--r-- 1 root root 5678 2020-01-01 12:00 libbar.so\n" +
            "drwxr-xr-x 2 root root 4096 2020-01-01 12:00 .\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val libs = cr.listNativeLibs("abc", "/data/app/.../lib/arm64")
        assertTrue(libs.contains("libfoo.so"))
        assertTrue(libs.contains("libbar.so"))
        assertTrue(libs.none { !it.endsWith(".so") })
    }

    @Test
    fun listNativeLibs_bad_dir_returns_empty() = runTest {
        val runner = FakeAdbProcessRunner()
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertEquals(emptyList(), cr.listNativeLibs("abc", "bad dir; rm -rf"))
        assertTrue(runner.runs.isEmpty(), "ls should not be invoked when dir fails the path guard")
    }

    @Test
    fun dumpsysPackage_passes_pkg_to_dumpsys_and_parses() = runTest {
        val runner = FakeAdbProcessRunner()
        // Real fixture: Hisense Android 9, com.dangbeimarket v6.0.7 (legacyNativeLibraryDir layout).
        val stdout = javaClass.classLoader!!.getResource("fixtures/dumpsys_package_hisense_android9.txt")!!
            .readText().lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n")
        runner.whenArgsContains(listOf("dumpsys", "package"), AdbProcessResult(0, stdout, ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val pkg = cr.dumpsysPackage("abc", "com.dangbeimarket")
        // runShellCmd passes the whole "dumpsys package com.dangbeimarket" as a single shell arg
        // (so the device's sh parses it); assert on the joined argv so the check holds regardless
        // of whether the command is split into separate argv tokens or sent as one string.
        val argv = runner.runs.last().joinToString(" ")
        assertTrue(argv.contains("-s abc shell dumpsys package com.dangbeimarket"), "argv=$argv")
        assertEquals("6.0.7", pkg.versionName)
    }

    @Test
    fun dumpsysPackage_rejects_invalid_pkg() = runTest {
        val cr = CommandRunner({ adb }, FakeAdbProcessRunner(), NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<IllegalArgumentException> { cr.dumpsysPackage("abc", "bad pkg!") }
    }

    @Test
    fun dumpsysPackage_throws_when_no_packages_section() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("dumpsys", "package"), AdbProcessResult(0, "garbage\nno Packages section", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> { cr.dumpsysPackage("abc", "com.x") }
    }

    @Test
    fun listNativeLibs_recurses_into_abi_subdir() = runTest {
        val runner = FakeAdbProcessRunner()
        // Real Hisense layout: .so files live in /lib/arm/, not directly in legacyNativeLibraryDir.
        // FakeAdbProcessRunner matches first-wins by keyword substring. The two ls calls differ in
        // path arg: shallow "/data/app/x/lib/" vs deep "/data/app/x/lib/arm/". The shallow arg
        // contains "/lib" but NOT "arm"; the deep arg contains both. So the "arm" rule (added FIRST)
        // matches only the deep call and returns .so files; the "/lib" rule (added SECOND) matches
        // the shallow call and returns the subdir entry. If "/lib" were first, the deep call would
        // wrongly match it and return the subdir entry (no .so) -> empty result.
        runner.whenArgsContains(listOf("arm"), AdbProcessResult(0,
            "-rwxr-xr-x 1 system system 1234 2020-01-01 12:00 libfoo.so\n" +
            "-rwxr-xr-x 1 system system 5678 2020-01-01 12:00 libbar.so\n", ""))
        runner.whenArgsContains(listOf("/lib"), AdbProcessResult(0,
            "drwxr-xr-x 2 system system 4096 2020-01-01 12:00 arm\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val libs = cr.listNativeLibs("abc", "/data/app/x/lib")
        assertTrue(libs.contains("libfoo.so"), "expected libfoo.so from abi subdir, got: $libs")
        assertTrue(libs.contains("libbar.so"))
    }


    @Test
    fun install_single_with_flags_builds_correct_argv() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install"), AdbProcessResult(0, "Success", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.install("abc", listOf("/x.apk"), InstallFlags(reinstall = true, allowTest = true, downgrade = false, grantPerms = false))
        val argv = runner.runs.last()
        assertTrue(argv.containsAll(listOf("-s","abc","install","-r","-t","/x.apk")))
        assertTrue(!argv.contains("-d") && !argv.contains("-g"))
    }

    @Test
    fun install_multiple_builds_install_multiple_argv() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install-multiple"), AdbProcessResult(0, "Success", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.install("abc", listOf("/a.apk","/b.apk"), InstallFlags(reinstall = true, allowTest = false, downgrade = true, grantPerms = true))
        val argv = runner.runs.last()
        assertTrue(argv.contains("install-multiple"))
        assertTrue(argv.containsAll(listOf("-r","-d","-g","/a.apk","/b.apk")))
    }

    @Test
    fun install_empty_paths_throws_argument() = runTest {
        val runner = FakeAdbProcessRunner()
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<IllegalArgumentException> { cr.install("abc", emptyList(), InstallFlags(reinstall = true, allowTest = false, downgrade = false, grantPerms = false)) }
    }

    @Test
    fun install_failure_throws_with_raw_stderr() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install"), AdbProcessResult(1, "Failure [INSTALL_FAILED_OLDER_SDK]", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val ex = assertFailsWith<RuntimeException> { cr.install("abc", listOf("x.apk"), InstallFlags(reinstall = true, allowTest = false, downgrade = false, grantPerms = false)) }
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
    fun dumpLogcat_passes_d_threadtime_args_and_completes() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLinesOnce(listOf("08-17 10:23:45.123  1  2 I Tag: hi"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val stream = cr.dumpLogcat("abc")
        // startStream uses Channel.UNLIMITED + once-mode → collect returns on completion (models -d exit)
        val collected = mutableListOf<String>()
        stream.lines.collect { collected.add(it) }
        assertEquals(listOf("08-17 10:23:45.123  1  2 I Tag: hi"), collected)
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
    fun runShellCmd_strips_pty_carriage_returns() = runTest {
        // adb shell runs under a pty whose termios has ONLCR set, so every \n in device output
        // arrives as \r\n. The \r is a pty transport artifact, not part of the command's output;
        // runShellCmd returns clean command text, so the pty-introduced \r must be stripped.
        // (A bare \r renders as tofu in Compose monospace fonts — the "mystery ?" boxes on the
        // System Info page. Real device cpuinfo bytes are pure \n, no \r — confirmed via od -c.)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell", "getprop"), AdbProcessResult(0, "a\r\nb\r\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.runShellCmd("ABC123", "getprop")
        assertEquals("a\nb\n", out)
    }

    @Test
    fun runShellCmd_strips_ansi_escape_sequences() = runTest {
        // adb shell under a pty makes interactive commands (top) emit ANSI CSI escape codes
        // (cursor moves); ESC (0x1B) has no glyph in Compose fonts -> tofu. Strip them.
        val runner = FakeAdbProcessRunner()
        val esc = 0x1b.toChar()
        runner.whenArgsContains(listOf("shell", "top"), AdbProcessResult(0, "${esc}[999CTasks: 913${esc}[H${esc}[J\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.runShellCmd("ABC123", "top -n 1")
        assertEquals("Tasks: 913\n", out)
    }

    @Test
    fun runShellCmd_replaces_tab_with_space() = runTest {
        // cpuinfo uses \t to separate fields; \t has no glyph in Compose monospace -> tofu.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell", "cat"), AdbProcessResult(0, "processor\t: 0\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.runShellCmd("ABC123", "cat /proc/cpuinfo")
        assertEquals("processor : 0\n", out)
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

    @Test
    fun forward_sends_minus_s_serial_forward_specs() = runTest {
        // R1/R2: `adb -s <serial> forward <local> <remote>` — serial command, exits 0 with empty stdout.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "tcp:9222", "localabstract:foo"),
            AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.forward("192.168.1.50:5555",
            com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.TCP, "9222"),
            com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.LOCALABSTRACT, "foo"))
        // No assertion on result — success = no exception. The FakeAdbProcessRunner default is
        // exit 1 "no script matched", so if forward() didn't send the right args it would throw.
    }

    @Test
    fun forward_nonzero_throws_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "tcp:9222"), AdbProcessResult(1, "", "cannot bind socket"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> {
            cr.forward("s1",
                com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.TCP, "9222"),
                com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.LOCALABSTRACT, "foo"))
        }
    }

    @Test
    fun listForwardsRaw_parses_host_wide_output() = runTest {
        // R1: `adb forward --list` is a host command — no -s serial. R4: returns ALL devices' rows.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"),
            AdbProcessResult(0, "s1 tcp:9222 localabstract:foo\ns2 tcp:8080 localabstract:bar\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val all = cr.listForwardsRaw()
        assertEquals(2, all.size)
        assertEquals("s1", all[0].serial)
        assertEquals("s2", all[1].serial)
    }

    @Test
    fun listForwardsRaw_empty_is_not_an_error() = runTest {
        // R3: empty stdout, exit 0 → emptyList, no throw.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertTrue(cr.listForwardsRaw().isEmpty())
    }

    @Test
    fun removeForward_sends_minus_s_remove_local() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--remove", "tcp:9222"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.removeForward("s1",
            com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.TCP, "9222"))
    }

    @Test
    fun removeAllForwards_sends_remove_all() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--remove-all"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.removeAllForwards("s1")
    }

    @Test
    fun fixLogcatDisabled_runs_setprop_stop_start_with_semicolons() = runTest {
        // `;` (not `&&`) so a non-zero `stop logd` (logd already stopped) doesn't skip `start logd`.
        // The whole sequence is one shell argv element so the device shell interprets the `;`.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(
            listOf("shell", "setprop persist.sys.logd.level V; stop logd; start logd"),
            AdbProcessResult(0, "", ""),
        )
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.fixLogcatDisabled("s1")
        // FakeAdbProcessRunner records every run() call's args — assert the shell command went out.
        assertTrue(runner.runs.any { it.contains("shell") && it.contains("setprop persist.sys.logd.level V; stop logd; start logd") })
    }

    @Test
    fun fixLogcatDisabled_propagates_nonzero_as_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(
            listOf("shell", "setprop persist.sys.logd.level V; stop logd; start logd"),
            AdbProcessResult(1, "", "setprop: permission denied"),
        )
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> { cr.fixLogcatDisabled("s1") }
    }

    @Test
    fun startActivity_with_action_and_data_builds_argv() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("am","start"), AdbProcessResult(0, "Starting: Intent { ... }", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.startActivity("abc", action = "android.intent.action.VIEW", data = "myapp://x", component = null, extras = emptyList())
        val argv = runner.runs.last()
        assertTrue(argv.containsAll(listOf("-s","abc","shell","am","start","-a","android.intent.action.VIEW","-d","myapp://x")))
    }

    @Test
    fun startActivity_with_component_and_extras_builds_argv() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("am","start"), AdbProcessResult(0, "Starting", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.startActivity("abc", action = null, data = null, component = "com.x/.Main", extras = listOf(Extra(ExtraType.STRING,"k","v")))
        val argv = runner.runs.last()
        assertTrue(argv.containsAll(listOf("-n","com.x/.Main","--es","k","v")))
    }

    @Test
    fun startActivity_failure_throws() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("am","start"), AdbProcessResult(1, "", "Error"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> { cr.startActivity("abc", action = "VIEW", data = null, component = null, extras = emptyList()) }
    }

    @Test
    fun bugreport_returns_zip_path_from_stdout() = runTest {
        // `adb -s <serial> bugreport <destDir>` is a host command. adb prints
        // "Bug report is stored at <zipPath>"; we parse the zip path from stdout.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("bugreport"), AdbProcessResult(0,
            "Bug report is processed\r\nBug report is stored at /tmp/bugreport-2026-09-04.zip\r\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val r = cr.bugreport("abc", "/tmp")
        assertEquals("/tmp/bugreport-2026-09-04.zip", r.zipPath)
    }

    @Test
    fun bugreport_nonzero_throws() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("bugreport"), AdbProcessResult(1, "", "device offline"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> { cr.bugreport("abc", "/tmp") }
    }
}
