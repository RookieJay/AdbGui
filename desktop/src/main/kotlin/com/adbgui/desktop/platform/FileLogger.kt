package com.adbgui.desktop.platform

import com.adbgui.core.log.LogEntry
import com.adbgui.core.log.LogLevel
import com.adbgui.core.log.Logger
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FileLogger(
    private val logDir: Path,
    override var level: LogLevel = LogLevel.INFO,
    private val clock: () -> Long,
) : Logger {
    private val maxBytes = 2L * 1024 * 1024
    private val maxFiles = 5
    private val current get() = logDir.resolve("adbgui.log")
    private val lock = Any()
    private val tsFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    init { Files.createDirectories(logDir) }

    override fun debug(msg: String, t: Throwable?) = write(LogLevel.DEBUG, msg, t)
    override fun info(msg: String, t: Throwable?) = write(LogLevel.INFO, msg, t)
    override fun warn(msg: String, t: Throwable?) = write(LogLevel.WARN, msg, t)
    override fun error(msg: String, t: Throwable?) = write(LogLevel.ERROR, msg, t)

    fun flush() { /* writes are synchronous; no-op */ }

    private fun write(lvl: LogLevel, msg: String, t: Throwable?) {
        if (lvl.ordinal < level.ordinal) return
        synchronized(lock) {
            maybeRoll()
            val line = buildString {
                append("[").append(tsFmt.format(Instant.ofEpochMilli(clock()))).append("] ")
                append("[").append(lvl.name).append("] ").append(msg)
                if (t != null) {
                    val sw = StringWriter(); t.printStackTrace(PrintWriter(sw)); append("\n").append(sw)
                }
                append("\n")
            }
            Files.writeString(current, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private fun maybeRoll() {
        if (!Files.exists(current)) return
        if (Files.size(current) < maxBytes) return
        for (i in (maxFiles - 1) downTo 1) {
            val from = logDir.resolve("adbgui.log.$i")
            val to = logDir.resolve("adbgui.log.${i + 1}")
            if (Files.exists(from)) { Files.deleteIfExists(to); Files.move(from, to) }
        }
        Files.move(current, logDir.resolve("adbgui.log.1"))
    }
}
