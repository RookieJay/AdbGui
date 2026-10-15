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
    /** 用户在下拉里选的应用；null = 无包过滤。非空时由 controller 的重解析循环维护 [pids]（Task 3）。 */
    val packageName: String? = null,
    /** 生效 pid 过滤（包的 pid 集合，或右键「只看此 PID」的单元素集合）。会话态，不持久化。 */
    val pids: Set<Int> = emptySet(),
    val tagInclude: String? = null,
    /** 多重排除：tag 命中任一（忽略大小写）即被滤掉。 */
    val tagExcludes: List<String> = emptyList(),
    val text: String? = null,
    /** When true, [text] is a regex (case-sensitive — embed `(?i)` for case-insensitive).
     * An invalid pattern falls back to plain-contains matching so the stream never dies. */
    val textIsRegex: Boolean = false,
)

class LogcatController(
    private val commands: CommandRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val ringCap: Int = 10000,
    /** `_lines` 的发布节流间隔。见 spec §4.2：必须是"固定间隔节流"而不是"取消式防抖"，
     *  否则持续洪流下 `delay` 会被不断取消、永不发布。 */
    private val publishIntervalMs: Long = 100,
    /** 包过滤激活期间 pidof 重解析间隔（spec D2）。测试传小值。 */
    private val reResolveIntervalMs: Long = 5000,
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
    @Volatile private var currentSerial: String? = null
    private var resolveJob: Job? = null
    @Volatile private var resolveLoopPkg: String? = null

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
        currentSerial = serial
        job = scope.launch {
            // The publisher is a CHILD of the stream job so stop() cancels it with the stream,
            // and so runTest can reach idle. It must never be launched from an init block.
            launch { publishLoop() }
            mutex.withLock {
                ring.clear(); filtered.clear()
                linesDirty = true
                _error.value = null
                // 设备切换 → pid 空间不同；包过滤激活时清空已解析 pids（与 ring 清除原子），
                // 重启循环的首拍会为新 serial 重新解析。放在 mutex 内保证与 mergePids 互斥。
                if (resolveLoopPkg != null) {
                    _filters.value = _filters.value.copy(pids = emptySet())
                }
            }
            // 必须在 mutex 块之后重启——确保 pids 清空对循环可见（先清后解析）。
            if (resolveLoopPkg != null) restartResolveLoop(resolveLoopPkg)
            flushSignal.trySend(Unit)
            runLoop(serial)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        resolveJob?.cancel(); resolveJob = null   // resolveLoopPkg 故意保留：下次 start() 重启
        stream?.kill(); stream = null
        currentSerial = null
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
            mutex.withLock {
                // 包过滤激活期间，pids 由解析循环拥有——UI 改其他条件（传空 pids）不得抹掉已解析集合
                val effective = if (f.packageName != null && f.packageName == _filters.value.packageName)
                    f.copy(pids = _filters.value.pids) else f
                _filters.value = effective
                recomputeFiltered(); linesDirty = true
            }
            publishOnce()
        }
        if (f.packageName != resolveLoopPkg) restartResolveLoop(f.packageName)
    }

    private fun restartResolveLoop(pkg: String?) {
        resolveJob?.cancel(); resolveJob = null
        resolveLoopPkg = pkg
        if (pkg != null) resolveJob = scope.launch { resolveLoop(pkg) }
    }

    /** spec D2：首拍立即解析，之后每 [reResolveIntervalMs] 重解析。pid **只增不减**（app 重启换
     *  pid 后旧 pid 的历史行仍显示）；解析失败保旧值不打断流；换包/清包由 [setFilters] 重启循环。 */
    private suspend fun resolveLoop(pkg: String) {
        while (true) {
            val serial = currentSerial
            if (serial != null) {
                val resolved = try {
                    commands.pidOf(serial, pkg)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logger.warn("pidof failed for $pkg on $serial: ${t.message}")  // 保旧 pids
                    null
                }
                if (resolved != null) mergePids(pkg, resolved)
            }
            delay(reResolveIntervalMs)
        }
    }

    private suspend fun mergePids(pkg: String, newPids: List<Int>) {
        mutex.withLock {
            val f = _filters.value
            if (f.packageName != pkg) return   // 解析期间用户已换包/清包，结果作废
            val merged = f.pids + newPids      // 空列表 → 无变化（app 未运行不清旧 pids）
            if (merged == f.pids) return
            _filters.value = f.copy(pids = merged)
            recomputeFiltered(); linesDirty = true
        }
        publishOnce()
    }

    /** 当前已发布（已过滤）视图里的 distinct tag→count，频次降序，cap 200。打开 Tag 下拉时调；
     *  非挂起——读 immutable List 快照无需持锁，且 tag 列表天然跟随当前过滤视图。 */
    fun snapshotTags(): List<Pair<String, Int>> {
        val counts = mutableMapOf<String, Int>()
        for (l in _lines.value) counts[l.tag] = (counts[l.tag] ?: 0) + 1
        return counts.entries.sortedByDescending { it.value }.take(200).map { it.key to it.value }
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
        f.tagExcludes.forEach { ex ->
            if (ex.isNotBlank() && line.tag.contains(ex, ignoreCase = true)) return false
        }
        if (f.pids.isNotEmpty() && line.pid !in f.pids) return false
        f.tagInclude?.takeIf { it.isNotBlank() }?.let { if (!line.tag.contains(it, ignoreCase = true)) return false }
        f.text?.takeIf { it.isNotBlank() }?.let { t ->
            val textOk = if (f.textIsRegex) {
                val re = compiledRegexFor(t)
                if (re != null) re.containsMatchIn(line.raw) else line.raw.contains(t, ignoreCase = true)
            } else {
                line.raw.contains(t, ignoreCase = true)
            }
            if (!textOk) return false
        }
        return true
    }

    // Regex cache: `matches` runs per line under the mutex, so a compile-per-line would dominate
    // under a log flood. Keyed by pattern text; null = pattern invalid → plain-contains fallback.
    // @Volatile per the cross-thread mutable-state rule (setFilters/onLine race window).
    @Volatile private var regexCacheKey: String? = null
    @Volatile private var regexCacheValue: Regex? = null

    private fun compiledRegexFor(pattern: String): Regex? {
        if (regexCacheKey != pattern) {
            regexCacheValue = try {
                pattern.toRegex()
            } catch (e: Exception) {
                logger.debug("logcat text regex invalid, falling back to plain contains: '$pattern' (${e.message})")
                null
            }
            regexCacheKey = pattern
        }
        return regexCacheValue
    }
}
