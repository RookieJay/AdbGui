package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.log.InMemoryLogger
import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbServerControllerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test
    fun ensureStarted_runs_start_server_once_and_caches() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("start-server"), AdbProcessResult(0, "adb server is running", ""))
        val logger = InMemoryLogger(LogLevel.DEBUG) { 0L }
        val ctrl = AdbServerController({ adb }, runner, logger)
        ctrl.ensureStarted()
        ctrl.ensureStarted() // cached: must not re-run start-server
        assertEquals(1, logger.entries.count { it.message.contains("server running") })
    }

    @Test
    fun ensureStarted_warns_on_nonzero_but_does_not_throw() = runTest {
        val runner = FakeAdbProcessRunner() // default exit 1
        val logger = InMemoryLogger(LogLevel.WARN) { 0L }
        val ctrl = AdbServerController({ adb }, runner, logger)
        ctrl.ensureStarted()
        assertTrue(logger.entries.any { it.level == LogLevel.WARN })
    }

    @Test
    fun invalidate_re_runs_start_server() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("start-server"), AdbProcessResult(0, "ok", ""))
        val logger = InMemoryLogger(LogLevel.DEBUG) { 0L }
        val ctrl = AdbServerController({ adb }, runner, logger)
        ctrl.ensureStarted()
        ctrl.invalidate()
        ctrl.ensureStarted()
        assertEquals(2, logger.entries.count { it.message.contains("server running") })
    }
}
