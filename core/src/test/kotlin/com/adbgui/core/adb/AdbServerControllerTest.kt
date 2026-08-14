package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.log.InMemoryLogger
import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AdbServerControllerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test
    fun ensureStarted_runs_start_server_and_logs_info() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("start-server"), AdbProcessResult(0, "adb server is running", ""))
        val logger = InMemoryLogger(LogLevel.INFO) { 0L }
        val ctrl = AdbServerController({ adb }, runner, logger)
        ctrl.ensureStarted()
        assert(logger.entries.any { it.message.contains("start-server") })
    }

    @Test
    fun ensureStarted_warns_on_nonzero_but_does_not_throw() = runTest {
        val runner = FakeAdbProcessRunner() // default exit 1
        val logger = InMemoryLogger(LogLevel.WARN) { 0L }
        val ctrl = AdbServerController({ adb }, runner, logger)
        ctrl.ensureStarted()
        assertTrue(logger.entries.any { it.level == LogLevel.WARN })
    }
}
