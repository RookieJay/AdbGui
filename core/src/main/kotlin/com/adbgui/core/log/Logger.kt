package com.adbgui.core.log

import kotlinx.serialization.Serializable

@Serializable
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val level: LogLevel,
    val message: String,
    val throwable: Throwable? = null,
    val timestamp: Long,
)

interface Logger {
    var level: LogLevel
    fun debug(msg: String, t: Throwable? = null)
    fun info(msg: String, t: Throwable? = null)
    fun warn(msg: String, t: Throwable? = null)
    fun error(msg: String, t: Throwable? = null)
}

class InMemoryLogger(
    override var level: LogLevel = LogLevel.INFO,
    private val clock: () -> Long,
) : Logger {
    private val _entries = mutableListOf<LogEntry>()
    val entries: List<LogEntry> get() = _entries.toList()

    private fun log(lvl: LogLevel, msg: String, t: Throwable?) {
        if (lvl.ordinal >= level.ordinal) _entries.add(LogEntry(lvl, msg, t, clock()))
    }
    override fun debug(msg: String, t: Throwable?) = log(LogLevel.DEBUG, msg, t)
    override fun info(msg: String, t: Throwable?) = log(LogLevel.INFO, msg, t)
    override fun warn(msg: String, t: Throwable?) = log(LogLevel.WARN, msg, t)
    override fun error(msg: String, t: Throwable?) = log(LogLevel.ERROR, msg, t)
}

object NoopLogger : Logger {
    override var level: LogLevel = LogLevel.INFO
    override fun debug(msg: String, t: Throwable?) {}
    override fun info(msg: String, t: Throwable?) {}
    override fun warn(msg: String, t: Throwable?) {}
    override fun error(msg: String, t: Throwable?) {}
}
