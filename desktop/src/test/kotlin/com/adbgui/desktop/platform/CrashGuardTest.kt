package com.adbgui.desktop.platform

import com.adbgui.core.log.InMemoryLogger
import com.adbgui.core.log.LogEntry
import com.adbgui.core.log.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CrashGuard must capture uncaught exceptions from any thread (including the AWT event
 * dispatch thread) and route them to the app log. Before it existed, the 2026-10-13
 * clipboard crash left zero log output, making post-mortem diagnosis guesswork.
 */
class CrashGuardTest {

    private class RecordingLogger : com.adbgui.core.log.Logger {
        override var level: LogLevel = LogLevel.INFO
        val entries = mutableListOf<LogEntry>()
        override fun debug(msg: String, t: Throwable?) {}
        override fun info(msg: String, t: Throwable?) {}
        override fun warn(msg: String, t: Throwable?) {}
        override fun error(msg: String, t: Throwable?) {
            entries.add(LogEntry(LogLevel.ERROR, msg, t, 0L))
        }
    }

    @Test
    fun `default handler logs uncaught exceptions with thread name`() {
        val logger = RecordingLogger()
        CrashGuard.install(logger)
        val boom = RuntimeException("boom")
        val worker = Thread { throw boom }
        worker.start()
        worker.join(5000)
        val entry = logger.entries.single()
        assertTrue(entry.message.contains(worker.name))
        assertEquals(boom, entry.throwable)
    }

    @Test
    fun `awt event dispatch handler logs throwable`() {
        val logger = RecordingLogger()
        CrashGuard.install(logger)
        val boom = IllegalStateException("cannot open system clipboard")
        AwtEventDispatchLogger().handle(boom)
        val entry = logger.entries.single()
        assertEquals(boom, entry.throwable)
    }

    @Test
    fun `install registers AWT exception handler class name`() {
        val logger = RecordingLogger()
        CrashGuard.install(logger)
        assertEquals(AwtEventDispatchLogger::class.java.name, System.getProperty("sun.awt.exception.handler"))
    }
}
