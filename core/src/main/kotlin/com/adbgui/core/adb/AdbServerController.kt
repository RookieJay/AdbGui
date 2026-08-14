package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.log.Logger

class AdbServerController(
    private val adb: suspend () -> AdbBinary,
    private val runner: AdbProcessRunner,
    private val logger: Logger,
) {
    suspend fun ensureStarted() {
        val bin = adb()
        val result = runner.run(bin, listOf("start-server"))
        if (result.exitCode == 0) {
            logger.info("adb start-server: server running")
        } else {
            logger.warn("adb start-server exit ${result.exitCode}: ${result.stderr.take(200)}")
        }
    }

    suspend fun restart() {
        val bin = adb()
        runner.run(bin, listOf("kill-server"))
        logger.info("adb server killed; restarting")
        ensureStarted()
    }
}
