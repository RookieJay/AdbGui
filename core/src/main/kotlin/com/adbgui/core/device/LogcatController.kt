package com.adbgui.core.device

import com.adbgui.core.adb.AdbStream
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.LogcatLineParser
import com.adbgui.core.domain.LogcatLine
import com.adbgui.core.domain.LogcatLevel
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** `_lines` 的发布节流间隔。见 spec §4.2：必须是"固定间隔节流"而不是"取消式防抖"，
     *  否则持续洪流下 `delay` 会被不断取消、永不发布。 */
    private val publishIntervalMs: Long = 100,
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
    @Volatile private var stream: AdbStream? = null
    private var job: Job? = null

    /** Conflated wake-up for [publishLoop]: a burst of N lines collapses into at most one pending
     *  signal, so the loop wakes once per [publishIntervalMs] instead of once per line. */
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var linesDirty = false

    // Mutex serializes ALL ring/filtered mutations (ArrayDeque is not thread-safe).
    // runLoop runs on the regular multi-threaded `scope` so its blocking readline() does NOT
    // hold the mutex (only onLine does, per line) — control calls (clear/setFilters) acquire
    // the mutex between lines, so they never starve (the prior limitedParallelism(1) confinement
    // starved them: the blocking readline pinned the single thread).
    private val mutex = Mutex()

    fun start(serial: String) {
        stop()
        job = scope.launch {
            // The publisher is a CHILD of the stream job so stop() cancels it with the stream,
            // and so runTest can reach idle. It must never be launched from an init block.
            launch { publishLoop() }
            mutex.withLock {
                ring.clear(); filtered.clear()
                linesDirty = true
                _error.value = null
            }
            flushSignal.trySend(Unit)
            runLoop(serial)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        stream?.kill(); stream = null
        linesDirty = false
        _status.value = LogcatStatus.IDLE
    }

    fun pause() { if (_status.value == LogcatStatus.RUNNING) _status.value = LogcatStatus.PAUSED }
    fun resume() { if (_status.value == LogcatStatus.PAUSED) _status.value = LogcatStatus.RUNNING }

    // clear()/setFilters() are explicit user actions: publish immediately rather than waiting for
    // the next throttle tick — and publishOnce() MUST be called outside mutex.withLock (it takes
    // the same non-reentrant mutex, so calling it inside would deadlock).
    fun clear() {
        scope.launch {
            mutex.withLock { ring.clear(); filtered.clear(); linesDirty = true }
            publishOnce()
        }
    }

    fun setFilters(f: LogcatFilters) {
        scope.launch {
            mutex.withLock { _filters.value = f; recomputeFiltered(); linesDirty = true }
            publishOnce()
        }
    }

    private fun recomputeFiltered() {
        val f = _filters.value
        filtered.clear()
        val it = ring.iterator()
        while (it.hasNext()) { val l = it.next(); if (matches(l, f)) filtered.addLast(l) }
    }

    /** The ONLY path that assigns `_lines` — snapshots [filtered] under the mutex, and only when
     *  something actually changed (a dirty flag keeps idle ticks from re-publishing). */
    private suspend fun publishOnce() {
        mutex.withLock {
            if (linesDirty) { _lines.value = filtered.toList(); linesDirty = false }
        }
    }

    /** Fixed-interval throttle, NOT a cancellable debounce: the conflated channel collapses a
     *  burst into one wake-up, and `delay` is never cancelled by new lines — so under a sustained
     *  flood it still publishes once per [publishIntervalMs] (a debounce would be starved forever). */
    private suspend fun publishLoop() {
        for (signal in flushSignal) {
            delay(publishIntervalMs)
            publishOnce()
        }
    }

    fun export(): String = _lines.value.joinToString("\n") { it.raw }

    /** One-shot full-buffer export via `adb logcat -d`: dumps the device's current logd ring
     *  (everything retained on-device, NOT bounded by [ringCap]), parses each line with
     *  [LogcatLineParser], and invokes [onLine] with the raw line for every line matching
     *  [filters] (same predicate the live view uses). The stream completes on EOF; the caller
     *  writes the raw lines to a file as they arrive — no full in-memory accumulation. */
    suspend fun dumpLogcat(serial: String, filters: LogcatFilters, onLine: (String) -> Unit) {
        val s = commands.dumpLogcat(serial)
        s.lines.mapNotNull { LogcatLineParser.parse(it) }.collect { line ->
            if (matches(line, filters)) onLine(line.raw)
        }
    }

    /** Re-enable logd on a device that ships with it silenced (e.g. TCL TVs). Does NOT restart
     *  the logcat stream — the caller does that via [start] afterwards (so the call site controls
     *  timing and is testable without a live stream). */
    suspend fun fixLogcatDisabled(serial: String): String {
        logger.info("[logcat] fix disabled logd serial=$serial")
        return commands.fixLogcatDisabled(serial)
    }

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

    private suspend fun onLine(line: LogcatLine) {
        if (_status.value == LogcatStatus.PAUSED) return
        mutex.withLock {
            ring.addLast(line)
            while (ring.size > ringCap) ring.removeFirst()
            if (matches(line, _filters.value)) {
                filtered.addLast(line)
                while (filtered.size > ringCap) filtered.removeFirst()
                linesDirty = true
            }
        }
        flushSignal.trySend(Unit)   // non-blocking, never suspends; outside the lock
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
