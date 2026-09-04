package com.adbgui.desktop.platform

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmAdbProcessRunnerSmokeTest {
    @Test
    fun runs_a_subprocess() = runTest {
        val runner = JvmAdbProcessRunner()
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val javaBin = AdbBinary(
            java.nio.file.Path.of(System.getProperty("java.home"), "bin", exe).toString(),
            AdbSource.PATH,
        )
        val r = runner.run(javaBin, listOf("-version"))
        assertTrue(r.exitCode == 0)
        assertTrue(r.stderr.contains("openjdk", ignoreCase = true))
    }

    @Test
    fun run_timeout_interrupts_long_running_process() = runTest {
        // Hermetic: no adb required. Uses a long-running OS binary as the "adb" path so the
        // timeout path (destroyForcibly + RuntimeException) is exercised without a real device.
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        val (bin, args) = if (isWindows) {
            AdbBinary("ping.exe", AdbSource.PATH) to listOf("-n", "30", "127.0.0.1")
        } else {
            AdbBinary("/bin/sleep", AdbSource.PATH) to listOf("30")
        }
        val runner = JvmAdbProcessRunner()
        var thrown: Throwable? = null
        try {
            runner.run(bin, args, timeoutMs = 200L)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is RuntimeException, "expected RuntimeException on timeout, got $thrown")
        assertTrue(thrown!!.message!!.contains("adb timeout"))
    }
}
