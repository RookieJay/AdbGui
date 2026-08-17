package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.ConnectResult
import com.adbgui.core.domain.DeviceProps
import com.adbgui.core.domain.InstallResult
import com.adbgui.core.domain.PackageInfo
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

    suspend fun disconnect(target: String): Boolean {
        server.ensureStarted()
        val r = runner.run(adb(), listOf("disconnect", target))
        return r.exitCode == 0
    }

    suspend fun listPackages(serial: String): List<PackageInfo> {
        val r = runCmd(serial, listOf("shell", "pm", "list", "packages", "-3"))
        return PackageListParser.parse(r.stdout, thirdPartyOnly = true)
    }

    suspend fun install(serial: String, apkPath: String, reinstall: Boolean): InstallResult {
        val args = buildList {
            add("install"); if (reinstall) add("-r"); add(apkPath)
        }
        val r = runCmd(serial, args)
        val parsed = InstallResultParser.parse(r.stdout, r.stderr, r.exitCode)
        if (!parsed.success) {
            throw AdbCommandException(command = "install ${args.joinToString(" ")}", exitCode = r.exitCode, stderr = r.stderr)
        }
        return parsed
    }

    suspend fun uninstall(serial: String, pkg: String): Boolean {
        val r = runCmd(serial, listOf("shell", "pm", "uninstall", pkg))
        return r.stdout.contains("Success")
    }

    suspend fun clearData(serial: String, pkg: String): Boolean {
        val r = runCmd(serial, listOf("shell", "pm", "clear", pkg))
        return r.stdout.contains("Success")
    }

    suspend fun deviceProps(serial: String): DeviceProps {
        val r = runCmd(serial, listOf("shell", "getprop"))
        return GetpropParser.parse(r.stdout, serial)
    }

    suspend fun screenshot(serial: String): ByteArray {
        server.ensureStarted()
        val raw = runner.runBinary(adb(), listOf("-s", serial, "exec-out", "screencap", "-p"))
        val png = extractPng(raw) ?: throw AdbCommandException(
            command = "adb -s $serial exec-out screencap -p",
            exitCode = -1,
            stderr = "no PNG signature in ${raw.size} bytes (prefix: ${raw.copyOfRange(0, minOf(80, raw.size)).decodeToString().replace("\n", "\\n")})",
        )
        logger.debug("adb screenshot ${png.size} bytes for $serial (stripped ${raw.size - png.size} banner bytes)")
        return png
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

    private suspend fun runCmd(serial: String, args: List<String>): AdbProcessResult {
        server.ensureStarted()
        val full = buildList { add("-s"); add(serial); addAll(args) }
        val cmd = "adb ${full.joinToString(" ")}"
        val r = runner.run(adb(), full)
        logger.debug("$cmd -> exit=${r.exitCode} err=${r.stderr.take(200)}")
        if (r.exitCode != 0) throw AdbCommandException(command = cmd, exitCode = r.exitCode, stderr = r.stderr)
        return r
    }
}
