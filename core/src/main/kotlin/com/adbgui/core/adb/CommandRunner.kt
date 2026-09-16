package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.BugreportResult
import com.adbgui.core.domain.ConnectResult
import com.adbgui.core.domain.DeviceProps
import com.adbgui.core.domain.DumpsysPackage
import com.adbgui.core.domain.Extra
import com.adbgui.core.domain.InstallFlags
import com.adbgui.core.domain.InstallResult
import com.adbgui.core.domain.PackageInfo
import com.adbgui.core.domain.RebootMode
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CoroutineScope

class CommandRunner(
    private val adb: suspend () -> AdbBinary,
    private val runner: AdbProcessRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val server: AdbServerStarter,
) {
    // seam so tests can inject a no-op server without the real AdbServerController
    fun interface AdbServerStarter { suspend fun ensureStarted() }

    suspend fun connect(ip: String, port: Int): ConnectResult {
        server.ensureStarted()
        val cmd = listOf("connect", "$ip:$port")
        val r = runner.run(adb(), cmd)
        logger.debug("adb ${cmd.joinToString(" ")} -> exit=${r.exitCode} out=${r.stdout.take(120)}")
        return ConnectResultParser.parse(r.stdout, r.stderr, r.exitCode)
    }

    suspend fun pair(ip: String, port: Int, code: String): com.adbgui.core.domain.PairResult {
        server.ensureStarted()
        val target = "$ip:$port"
        val cmd = listOf("pair", target, code)
        val r = runner.run(adb(), cmd)
        // Don't log the pairing code — it's a short-lived secret (CLAUDE.md: no sensitive data in logs).
        logger.debug("adb pair $target <code-redacted> -> exit=${r.exitCode} out=${r.stdout.take(120)}")
        return PairResultParser.parse(r.stdout, r.stderr, r.exitCode)
    }

    suspend fun disconnect(target: String): Boolean {
        server.ensureStarted()
        val r = runner.run(adb(), listOf("disconnect", target))
        return r.exitCode == 0
    }

    /** `adb version` — host command (no -s serial, no adb server): reports the client binary version.
     *  No Parser: returns raw stdout for the Settings page to display. */
    suspend fun adbVersion(): String {
        val cmd = listOf("version")
        val r = runner.run(adb(), cmd)
        logger.debug("adb ${cmd.joinToString(" ")} -> exit=${r.exitCode} out=${r.stdout.take(120)}")
        if (r.exitCode != 0) throw AdbCommandException(command = "adb version", exitCode = r.exitCode, stderr = r.stderr)
        return r.stdout.trim()
    }

    /** Run an arbitrary device-shell command string and return raw stdout.
     *  The whole `cmd` is passed as a single `shell` argument so the device's /system/bin/sh
     *  interprets pipes/redirects. No Parser — output is for humans, returned verbatim
     *  except the pty's carriage returns: `adb shell` runs under a pty with ONLCR, so every
     *  `\n` arrives as `\r\n`; the `\r` is a transport artifact (not command output) and is
     *  stripped here, so callers see clean `\n`-terminated lines. (A bare `\r` renders as
     *  tofu in Compose monospace fonts.) Throws AdbCommandException on non-zero exit
     *  (commands where non-zero is expected, e.g. grep-no-match, should append `|| true`). */
    suspend fun runShellCmd(serial: String, cmd: String): String {
        return sanitizeShellOutput(runCmd(serial, listOf("shell", cmd)).stdout)
    }

    /** Re-enable logd after a device shipped with it silenced (e.g. TCL Android TVs set
     *  `persist.sys.logd.level=S` at factory → `adb logcat` is empty). Runs setprop + stop/start
     *  logd as one shell line with `;` so a non-zero `stop logd` (already stopped) doesn't skip
     *  `start logd`. The caller MUST restart its logcat stream afterwards — restarting logd
     *  breaks the existing stream. Throws AdbCommandException on non-zero exit. */
    suspend fun fixLogcatDisabled(serial: String): String {
        return runShellCmd(serial, "setprop persist.sys.logd.level V; stop logd; start logd")
    }

    /** Normalize device-shell stdout for display:
     *  - strip ANSI escape sequences (CSI: `ESC[...letter`) — interactive commands like
     *    `top` emit these under the pty; ESC has no glyph in Compose fonts -> tofu;
     *  - drop `\r` (pty ONLCR turns \n into \r\n — transport artifact);
     *  - replace other control chars (< 0x20 except \n, and 0x7F) with space — e.g. `\t`
     *    (cpuinfo field separator) has no glyph in Compose monospace -> tofu;
     *  - keep `\n`, printable ASCII, and high-byte UTF-8 (CJK etc.) intact. */
    private fun sanitizeShellOutput(raw: String): String {
        val stripped = ANSI_CSI.replace(raw) { "" }
        val sb = StringBuilder(stripped.length)
        for (c in stripped) {
            val code = c.code
            when {
                code == 0x0A -> sb.append(c)                        // \n preserved
                code == 0x0D || code == 0x7F -> { }                 // \r / DEL dropped (pty artifact)
                code < 0x20 -> sb.append(' ')                       // other control chars -> space (\t etc.)
                else -> sb.append(c)                                // printable ASCII + UTF-8
            }
        }
        return sb.toString()
    }

    private companion object {
        // CSI sequences: ESC [ ... (params) final-byte. Covers cursor moves, colors, erase, etc.
        val ANSI_CSI = Regex("\\[[0-9;?]*[A-Za-z]")
    }

    // Rejects paths containing shell metacharacters / whitespace — used by listNativeLibs
    // to guard `adb shell ls -la <dir>` against shell-injection-shaped input.
    private val safePathRegex = Regex("^[^\\s;&|`'$<>]+$")

    // Package-name guard for dumpsysPackage (and any future per-package adb command). Allows letters,
    // digits, dot, underscore — the Android package-name charset. Rejects spaces / shell metachars
    // so a malformed caller cannot shape `adb shell dumpsys package <pkg>` into a sh injection.
    private val pkgRegex = Regex("^[A-Za-z0-9._]+$")

    suspend fun listPackages(serial: String): List<PackageInfo> {
        // -f: all packages with APK path (for path-based system detection fallback).
        // -s: system packages only — authoritative source for isSystem, covers UPDATED_SYSTEM_APP
        //     (APK in /data/app/ but Android still flags it as system; path-only detection misses these).
        val full = runCmd(serial, listOf("shell", "pm", "list", "packages", "-f")).stdout
        val sys  = runCmd(serial, listOf("shell", "pm", "list", "packages", "-s")).stdout
        return PackageListParser.parse(full, sys)
    }

    /** `adb shell dumpsys package <pkg>` → parsed [DumpsysPackage]. Throws [AdbCommandException]
     *  if the output has no parseable `Package [` / `Packages:` section (e.g. package not installed
     *  on the device). `runShellCmd` sanitizes the pty's `\r`/ANSI artifacts before parsing. */
    suspend fun dumpsysPackage(serial: String, pkg: String): DumpsysPackage {
        require(pkgRegex.matches(pkg)) { "invalid package name: $pkg" }
        val out = runShellCmd(serial, "dumpsys package $pkg")
        val parsed = DumpsysPackageParser.parse(out)
            ?: throw AdbCommandException(
                command = "adb -s $serial shell dumpsys package $pkg",
                exitCode = -1,
                stderr = "no Package section in dumpsys output; stdout head=${out.take(200)}",
            )
        return parsed
    }

    suspend fun install(serial: String, paths: List<String>, flags: InstallFlags): InstallResult {
        require(paths.isNotEmpty()) { "install: paths must not be empty" }
        val subcmd = if (paths.size == 1) "install" else "install-multiple"
        val args = buildList {
            add(subcmd)
            if (flags.reinstall) add("-r")
            if (flags.allowTest) add("-t")
            if (flags.downgrade) add("-d")
            if (flags.grantPerms) add("-g")
            addAll(paths)
        }
        val r = runCmd(serial, args)
        val parsed = InstallResultParser.parse(r.stdout, r.stderr, r.exitCode)
        if (!parsed.success) {
            throw AdbCommandException(command = "adb -s $serial ${args.joinToString(" ")}", exitCode = r.exitCode, stderr = r.stderr)
        }
        return parsed
    }

    suspend fun uninstall(serial: String, pkg: String): Boolean {
        val r = runCmd(serial, listOf("shell", "pm", "uninstall", pkg))
        return r.stdout.contains("Success")
    }

    suspend fun grant(serial: String, pkg: String, perm: String): String =
        runCmd(serial, listOf("shell", "pm", "grant", pkg, perm)).stdout

    suspend fun revoke(serial: String, pkg: String, perm: String): String =
        runCmd(serial, listOf("shell", "pm", "revoke", pkg, perm)).stdout

    suspend fun clearData(serial: String, pkg: String): Boolean {
        val r = runCmd(serial, listOf("shell", "pm", "clear", pkg))
        return r.stdout.contains("Success")
    }

    suspend fun deviceProps(serial: String): DeviceProps {
        val r = runCmd(serial, listOf("shell", "getprop"))
        var props = GetpropParser.parse(r.stdout, serial)
        // Resolution isn't in getprop on most devices — use wm size
        val wmSizeRe = Regex("Physical size: (\\d+x\\d+)")
        runCatching { runner.run(adb(), listOf("-s", serial, "shell", "wm", "size")).stdout }
            .getOrNull()?.let { out ->
                wmSizeRe.find(out)?.groupValues?.get(1)?.let { res ->
                    props = props.copy(resolution = res)
                }
            }
        return props
    }

    suspend fun screenshot(serial: String): ByteArray {
        server.ensureStarted()
        logger.info("[screenshot] adb exec-out screencap start serial=$serial")
        val raw = runner.runBinary(adb(), listOf("-s", serial, "exec-out", "screencap", "-p"), timeoutMs = 30_000L)
        logger.info("[screenshot] adb returned raw=${raw.size} bytes")
        val png = extractPng(raw) ?: throw AdbCommandException(
            command = "adb -s $serial exec-out screencap -p",
            exitCode = -1,
            stderr = "no PNG signature in ${raw.size} bytes (prefix: ${raw.copyOfRange(0, minOf(80, raw.size)).decodeToString().replace("\n", "\\n")})",
        )
        val dims = pngDimensions(png)
        logger.info("[screenshot] png ${dims?.first ?: "?"}x${dims?.second ?: "?"} ${png.size} bytes (stripped ${raw.size - png.size} banner)")
        return png
    }

    /** Reads the IHDR width/height from a PNG byte array (big-endian uint32 at offsets 16/20).
     *  Null if the bytes are too short / not a PNG. Pure byte parse, no image deps. */
    private fun pngDimensions(png: ByteArray): Pair<Int, Int>? {
        if (png.size < 24) return null
        val w = (png[16].toInt() and 0xff shl 24) or (png[17].toInt() and 0xff shl 16) or
            (png[18].toInt() and 0xff shl 8) or (png[19].toInt() and 0xff)
        val h = (png[20].toInt() and 0xff shl 24) or (png[21].toInt() and 0xff shl 16) or
            (png[22].toInt() and 0xff shl 8) or (png[23].toInt() and 0xff)
        return w to h
    }

    suspend fun deviceDetailReport(serial: String): String {
        server.ensureStarted()
        val sections = listOf(
            "getprop" to listOf("getprop"),
            "wm size" to listOf("wm", "size"),
            "wm density" to listOf("wm", "density"),
            "meminfo" to listOf("cat", "/proc/meminfo"),
            "cpuinfo" to listOf("cat", "/proc/cpuinfo"),
            "battery" to listOf("dumpsys", "battery"),
            "disk (/data)" to listOf("df", "/data"),
        )
        val sb = StringBuilder()
        sb.appendLine("Serial: $serial")
        for ((label, shellArgs) in sections) {
            sb.appendLine()
            sb.appendLine("===== $label =====")
            val full = listOf("-s", serial, "shell") + shellArgs
            try {
                val r = runner.run(adb(), full)
                if (r.exitCode == 0) {
                    sb.appendLine(r.stdout.trimEnd())
                } else {
                    sb.appendLine("[exit ${r.exitCode}] ${r.stderr.trim()}")
                }
            } catch (e: Exception) {
                sb.appendLine("[error] ${e.message}")
            }
            logger.debug("deviceDetailReport section '$label' (exit ok)")
        }
        return sb.toString()
    }

    suspend fun streamLogcat(serial: String): AdbStream {
        server.ensureStarted()
        return runner.startStream(adb(), listOf("-s", serial, "logcat", "-v", "threadtime"), scope)
    }

    /** `adb -s <serial> logcat -d -v threadtime` — one-shot dump of the device's current logd
     *  ring buffer, then the process exits (the stream's flow completes on EOF). Used by
     *  LogcatController.dumpLogcat for full-buffer export that is NOT capped by the in-memory
     *  ring. The live [streamLogcat] is preferred for viewing; this is for "export everything". */
    suspend fun dumpLogcat(serial: String): AdbStream {
        server.ensureStarted()
        return runner.startStream(adb(), listOf("-s", serial, "logcat", "-d", "-v", "threadtime"), scope)
    }

    suspend fun reboot(serial: String, mode: RebootMode): String {
        val args = buildList { add("reboot"); if (mode.arg != null) add(mode.arg) }
        return runCmd(serial, args).stdout   // throws AdbCommandException on nonzero (e.g. device offline)
    }

    suspend fun root(serial: String): String {
        return runCmd(serial, listOf("root")).stdout   // production: "adbd cannot run as root in production builds"
    }

    suspend fun remount(serial: String): String {
        return runCmd(serial, listOf("remount")).stdout
    }

    suspend fun inputKey(serial: String, keycode: Int) {
        runCmd(serial, listOf("shell", "input", "keyevent", keycode.toString()))
    }

    /** `adb shell input text <text>` — types text into the focused field on the device. The text is
     *  passed as a single argv element so spaces survive (adb's modern shell protocol sends the argv
     *  array without `sh -c` re-parsing, so the string reaches the device `input` binary intact). */
    suspend fun inputText(serial: String, text: String) {
        runCmd(serial, listOf("shell", "input", "text", text))
    }

    suspend fun forceStop(serial: String, pkg: String): String {
        return runCmd(serial, listOf("shell", "am", "force-stop", pkg)).stdout
    }

    suspend fun startApp(serial: String, pkg: String): String {
        return runCmd(serial, listOf("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")).stdout
    }

    /** `am start` with optional action/data/component + extras. At least one of action/component
     *  should be non-blank (VM guards); core doesn't enforce — adb will error if both blank. */
    suspend fun startActivity(
        serial: String,
        action: String?,
        data: String?,
        component: String?,
        extras: List<Extra>,
    ): String {
        val args = buildList {
            add("shell"); add("am"); add("start")
            if (!action.isNullOrBlank()) { add("-a"); add(action) }
            if (!data.isNullOrBlank()) { add("-d"); add(data) }
            if (!component.isNullOrBlank()) { add("-n"); add(component) }
            extras.forEach { add(it.type.flag); add(it.key); add(it.value) }
        }
        return runCmd(serial, args).stdout
    }

    suspend fun sendBroadcast(serial: String, action: String, uri: String?, extras: List<Extra>): String {
        val args = buildList {
            add("shell"); add("am"); add("broadcast"); add("-a"); add(action)
            if (uri != null) { add("-d"); add(uri) }
            extras.forEach { add(it.type.flag); add(it.key); add(it.value) }
        }
        return runCmd(serial, args).stdout
    }

    suspend fun queryProvider(serial: String, uri: String, where: String?): String {
        val args = buildList {
            add("shell"); add("content"); add("query"); add("--uri"); add(uri)
            if (where != null) { add("--where"); add(where) }
        }
        return runCmd(serial, args).stdout
    }

    suspend fun ls(serial: String, path: String): String {
        // Trailing slash is critical: `ls -la /sdcard` (symlink) shows the link itself;
        // `ls -la /sdcard/` follows the link and lists the directory contents.
        val p = if (path.endsWith("/")) path else "$path/"
        return runCmd(serial, listOf("shell", "ls", "-la", p)).stdout
    }

    /** List `.so` files in a device directory (typically a package's `nativeLibraryDir`).
     *  Reuses [LsParser] — no new parser. The `dir` is validated against [safePathRegex] to keep
     *  `adb shell ls -la <dir>` from being a shell-injection vector (adb's modern shell protocol
     *  sends argv without `sh -c` re-parsing, but a path with `;`/`&`/backticks is still suspect
     *  and never a real lib path). On any ls failure (bad dir, offline) returns empty list —
     *  native libs are best-effort display, not a critical path.
     *
     *  Handles two on-device layouts:
     *  - Android >=10: `.so` files live directly in `nativeLibraryDir`.
     *  - Android <=9: `.so` files live in `/lib/<abi>/` (e.g. `/lib/arm/`), one level below
     *    `legacyNativeLibraryDir`. The flat `ls` of the dir returns subdirectory entries (e.g.
     *    `arm`) and no `.so`; recurse one level into each immediate subdir to collect `.so`. */
    suspend fun listNativeLibs(serial: String, dir: String): List<String> {
        if (!safePathRegex.matches(dir)) {
            logger.warn("listNativeLibs: rejecting dir: $dir")
            return emptyList()
        }
        val out = runCatching { ls(serial, dir) }.getOrElse { return emptyList() }
        val entries = LsParser.parse(out)
        val direct = entries.map { it.name }.filter { it.endsWith(".so") }
        if (direct.isNotEmpty()) return direct
        // Android <=9 abi-subdir layout: .so files live in /lib/<abi>/ subdir.
        val subdirs = entries.filter { it.isDirectory }
        return subdirs.flatMap { sd ->
            runCatching { LsParser.parse(ls(serial, "$dir/${sd.name}")) }
                .getOrDefault(emptyList())
                .map { it.name }.filter { it.endsWith(".so") }
        }
    }

    suspend fun checkSymlinkDirs(serial: String, paths: List<String>): List<Boolean> {
        if (paths.isEmpty()) return emptyList()
        server.ensureStarted()
        val script = paths.joinToString("; ") { p -> "test -d \"$p\" && echo 1 || echo 0" }
        val r = runner.run(adb(), listOf("-s", serial, "shell", script))
        // adb shell (pty) can emit \r\r\n per line (e.g. TCL Android 6.0); lineSequence() then yields
        // value + empty lines, so filter blanks to keep the boolean list aligned with `paths`.
        return r.stdout.lineSequence().filter { it.isNotBlank() }.map { it.trim() == "1" }.toList()
    }

    suspend fun push(serial: String, localPath: String, devicePath: String) {
        runCmd(serial, listOf("push", localPath, devicePath))
    }

    suspend fun pull(serial: String, devicePath: String, localPath: String) {
        runCmd(serial, listOf("pull", devicePath, localPath))
    }

    /** Probe `/proc/net/unix` for the WebView devtools abstract socket
     *  (`webview_devtools_remote_<pid>`). Returns the bare socket name (no `@` prefix) for use
     *  with `adb forward localabstract:<name>`, or null when no WebView is on screen. */
    suspend fun webviewSocket(serial: String): String? {
        val out = runShellCmd(serial, "cat /proc/net/unix")
        return WebviewSocketParser.parse(out)
    }

    /** `adb -s <serial> forward <local> <remote>`. Exits 0 with empty stdout on success (R2);
     *  on failure adb writes stderr + non-zero → runCmd throws AdbCommandException (surfaced inline by UI). */
    suspend fun forward(serial: String, local: com.adbgui.core.domain.ForwardSpec, remote: com.adbgui.core.domain.ForwardSpec) {
        runCmd(serial, listOf("forward", local.adbForm(), remote.adbForm()))
    }

    /** `adb forward --list` — host command (no -s serial), lists ALL devices' forwards (R1).
     *  Returns the parsed rows unfiltered; DeviceRepository.listForwards filters by serial (R4).
     *  Empty result is not an error (R3); non-zero exit is. */
    suspend fun listForwardsRaw(): List<com.adbgui.core.domain.ForwardEntry> {
        server.ensureStarted()
        val cmd = listOf("forward", "--list")
        val r = runner.run(adb(), cmd)
        logger.debug("adb ${cmd.joinToString(" ")} -> exit=${r.exitCode} out=${r.stdout.take(200)}")
        if (r.exitCode != 0) throw AdbCommandException(command = "adb forward --list", exitCode = r.exitCode, stderr = r.stderr)
        return ForwardListParser.parse(r.stdout)
    }

    /** `adb -s <serial> forward --remove <local>`. */
    suspend fun removeForward(serial: String, local: com.adbgui.core.domain.ForwardSpec) {
        runCmd(serial, listOf("forward", "--remove", local.adbForm()))
    }

    /** `adb -s <serial> forward --remove-all`. */
    suspend fun removeAllForwards(serial: String) {
        runCmd(serial, listOf("forward", "--remove-all"))
    }

    /** `adb -s <serial> bugreport <destDir>` — host command (not shell). adb writes
     *  `bugreport-<date>.zip` into destDir and prints the path to stdout. Long-running (10–60s+):
     *  passes a 180s timeout (JvmAdbProcessRunner.run honors it via async reads after T5). */
    suspend fun bugreport(serial: String, destDir: String): BugreportResult {
        val r = runCmd(serial, listOf("bugreport", destDir), timeoutMs = 180_000L)
        val zip = extractBugreportPath(r.stdout, destDir)
            ?: throw AdbCommandException(
                command = "adb -s $serial bugreport $destDir",
                exitCode = r.exitCode,
                stderr = "no bugreport zip path found in stdout; destDir=$destDir; stdout head=${r.stdout.take(200)}",
            )
        return BugreportResult(zipPath = zip)
    }

    /** Parse the zip path from `adb bugreport` stdout ("Bug report is stored at <path>"); fall back to
     *  scanning destDir for the newest bugreport-*.zip (real-device path; in tests stdout always carries it). */
    private fun extractBugreportPath(stdout: String, destDir: String): String? {
        val re = Regex("Bug report is stored at:?\\s*(\\S+)")
        re.find(stdout)?.let { return it.groupValues[1].trim() }
        val dir = java.io.File(destDir)
        return dir.listFiles { f -> f.name.startsWith("bugreport-") && f.extension.equals("zip", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }?.absolutePath
    }

    private fun extractPng(bytes: ByteArray): ByteArray? {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val start = indexOf(bytes, sig) ?: return null
        return if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        if (haystack.size < needle.size) return null
        outer@ for (i in 0..haystack.size - needle.size) {
            var j = 0
            while (j < needle.size) { if (haystack[i + j] != needle[j]) continue@outer; j++ }
            return i
        }
        return null
    }

    private suspend fun runCmd(serial: String, args: List<String>, timeoutMs: Long? = null): AdbProcessResult {
        server.ensureStarted()
        val full = buildList { add("-s"); add(serial); addAll(args) }
        val cmd = "adb ${full.joinToString(" ")}"
        val r = runner.run(adb(), full, timeoutMs)
        logger.debug("$cmd -> exit=${r.exitCode} err=${r.stderr.take(200)}")
        if (r.exitCode != 0) throw AdbCommandException(command = cmd, exitCode = r.exitCode, stderr = r.stderr)
        return r
    }
}
