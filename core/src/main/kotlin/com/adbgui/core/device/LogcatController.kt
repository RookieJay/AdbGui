package com.adbgui.core.device

import com.adbgui.core.adb.AdbStream
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.LogcatLineParser
import com.adbgui.core.domain.LogcatLine
import com.adbgui.core.domain.LogcatLevel
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

enum class LogcatStatus { IDLE, RUNNING, PAUSED, RECONNECTING, FAILED }

data class LogcatFilters(
    val levelSet: Set<LogcatLevel> = LogcatLevel.entries.toSet(),
    val tagInclude: String? = null,
    val tagExclude: String? = null,
    val text: String? = null,
    val pid: Int? = null,
)

class LogcatController(
    private val commands: CommandRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val ringCap: Int = 10000,
) {
    private val _lines = MutableStateFlow<List<LogcatLine>>(emptyList())
    val lines: StateFlow<List<LogcatLine>> = _lines.asStateFlow()
    private val _filters = MutableStateFlow(LogcatFilters())
    val filters: StateFlow<LogcatFilters> = _filters.asStateFlow()
    private val _status = MutableStateFlow(LogcatStatus.IDLE)
    val status: StateFlow<LogcatStatus> = _status.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val ring = ArrayDeque<LogcatLine>()
    private val filtered = ArrayDeque<LogcatLine>()
    private var stream: AdbStream? = null
    private var job: Job? = null
    // Single-thread dispatcher serializing all ring/filtered mutations (ArrayDeque is not
    // thread-safe; onLine runs here from runLoop, and clear()/start() route here too).
    // Derived from the scope's dispatcher (the test scheduler under runTest — so
    // advanceUntilIdle controls it; limited to parallelism 1 for production safety).
    private val serialDispatcher: CoroutineDispatcher =
        (scope.coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default).limitedParallelism(1)

    fun start(serial: String) {
        stop()
        job = scope.launch(serialDispatcher) {
            ring.clear(); filtered.clear(); _lines.value = emptyList(); _error.value = null
            runLoop(serial)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        stream?.kill(); stream = null
        _status.value = LogcatStatus.IDLE
    }

    fun pause() { if (_status.value == LogcatStatus.RUNNING) _status.value = LogcatStatus.PAUSED }
    fun resume() { if (_status.value == LogcatStatus.PAUSED) _status.value = LogcatStatus.RUNNING }

    fun clear() {
        // Route onto serialDispatcher so deque mutations serialize with runLoop's onLine.
        scope.launch(serialDispatcher) { ring.clear(); filtered.clear(); _lines.value = emptyList() }
    }

    // setFilters routes through serialDispatcher so deque mutations serialize with onLine
    // (Task 4 carry-over: ArrayDeque is not thread-safe; the UI thread must not mutate
    // filtered/ring/_lines concurrently with runLoop's onLine on serialDispatcher).
    fun setFilters(f: LogcatFilters) {
        scope.launch(serialDispatcher) {
            _filters.value = f
            recomputeFiltered()
        }
    }

    private fun recomputeFiltered() {
        val f = _filters.value
        filtered.clear()
        val it = ring.iterator()
        while (it.hasNext()) { val l = it.next(); if (matches(l, f)) filtered.addLast(l) }
        _lines.value = filtered.toList()
    }

    fun export(): String = _lines.value.joinToString("\n") { it.raw }

    private suspend fun runLoop(serial: String) {
        var backoff = 1000L
        var failures = 0
        while (true) {
            try {
                val s = commands.streamLogcat(serial)
                stream = s
                if (_status.value != LogcatStatus.PAUSED) _status.value = LogcatStatus.RUNNING
                s.lines.mapNotNull { LogcatLineParser.parse(it) }.collect { onLine(it) }
                logger.warn("logcat stream ended for $serial")
                failures++
            } catch (t: Throwable) {
                if (t is CancellationException) throw t  // let stop()'s cancellation propagate cleanly
                logger.warn("logcat stream error for $serial: ${t.message}")
                _error.value = t.message
                failures++
            }
            if (failures >= 3) _status.value = LogcatStatus.FAILED
            else _status.value = LogcatStatus.RECONNECTING
            if (failures >= 3) failures = 0  // keep retrying after FAILED
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun onLine(line: LogcatLine) {
        if (_status.value == LogcatStatus.PAUSED) return
        ring.addLast(line)
        while (ring.size > ringCap) ring.removeFirst()
        if (matches(line, _filters.value)) {
            filtered.addLast(line)
            while (filtered.size > ringCap) filtered.removeFirst()
            _lines.value = filtered.toList()
        }
    }

    private fun matches(line: LogcatLine, f: LogcatFilters): Boolean {
        if (line.level !in f.levelSet) return false
        f.tagInclude?.takeIf { it.isNotBlank() }?.let { if (!line.tag.contains(it, ignoreCase = true)) return false }
        f.tagExclude?.takeIf { it.isNotBlank() }?.let { if (line.tag.contains(it, ignoreCase = true)) return false }
        f.text?.takeIf { it.isNotBlank() }?.let { if (!line.raw.contains(it, ignoreCase = true)) return false }
        f.pid?.let { if (line.pid != it) return false }
        return true
    }
}
