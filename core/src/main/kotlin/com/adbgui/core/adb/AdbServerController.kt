package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.log.Logger

class AdbServerController(
    private val adb: suspend () -> AdbBinary,
    private val runner: AdbProcessRunner,
    private val logger: Logger,
) {
    // Cached across calls so we don't fork `adb start-server` on every command (it was
    // flooding the log and forking a process per adb call). @Volatile: read/written from
    // multiple coroutine scopes. Invalidated by [invalidate] when a command fails, so a
    // server killed externally is re-started within the next tracker poll (~2s).
    @Volatile private var started = false

    suspend fun ensureStarted() {
        if (started) return
        val bin = adb()
        val result = runner.run(bin, listOf("start-server"))
        if (result.exitCode == 0) {
            started = true
            logger.debug("adb start-server: server running")
        } else {
            logger.warn("adb start-server exit ${result.exitCode}: ${result.stderr.take(200)}")
        }
    }

    /** Drops the cached "started" flag so the next [ensureStarted] re-runs `adb start-server`.
     *  Call when a command fails in a way that suggests the server died. */
    fun invalidate() { started = false }

    suspend fun restart() {
        val bin = adb()
        runner.run(bin, listOf("kill-server"))
        started = false
        logger.info("adb server killed; restarting")
        ensureStarted()
    }
}
