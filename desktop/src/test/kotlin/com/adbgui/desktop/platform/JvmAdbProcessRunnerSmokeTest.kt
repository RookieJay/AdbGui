package com.adbgui.desktop.platform

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
}
