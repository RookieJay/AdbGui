package com.adbgui.core.device

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.log.Logger
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface ManualRecordState {
    data object Idle : ManualRecordState
    data class Recording(val serial: String, val file: Path, val lineCount: Long, val bytesWritten: Long) : ManualRecordState
    data class Stopped(val file: Path, val lineCount: Long, val bytesWritten: Long, val reason: String?) : ManualRecordState
}

/** 录制编排层（spec §3.2）：手动会话（一次一个）+ 自动后台录制 + 启动清理 + 轮转续开。 */
class LogcatRecordingManager(
    private val commands: CommandRunner,
    private val tracker: IDeviceTracker,
    private val settings: SettingsStore,
    private val history: DeviceHistoryStore,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val defaultDir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rotationBytes: Long = LogcatRecorder.ROTATION_BYTES,
) {
    private val _recordingsDir = MutableStateFlow(effectiveDir(settings.state.value))
    val recordingsDir: Path get() = _recordingsDir.value   // 公开 API 形状不变（LogcatViewModel 取值即最新）

    private fun effectiveDir(s: com.adbgui.core.settings.Settings): Path =
        s.logcatRecordingsDir?.let { raw ->
            runCatching { Path.of(raw) }
                .onFailure { logger.warn("invalid recordings dir '$raw'; using default") }
                .getOrNull()
        } ?: defaultDir
    private val _manualState = MutableStateFlow<ManualRecordState>(ManualRecordState.Idle)
    val manualState: StateFlow<ManualRecordState> = _manualState.asStateFlow()

    private val _autoStates = MutableStateFlow<Map<String, RecordState>>(emptyMap())
    val autoStates: StateFlow<Map<String, RecordState>> = _autoStates.asStateFlow()

    private var manualRecorder: LogcatRecorder? = null
    private var manualJob: Job? = null
    private var manualFile: Path? = null
    private val mutex = Mutex()
    private val autoRecorders = mutableMapOf<String, LogcatRecorder>()   // mutex 保护
    private val autoJobs = mutableMapOf<String, Job>()                   // mutex 保护
    private val autoFiles = mutableMapOf<String, Path>()                 // mutex 保护（watchdog 检查文件存活用）
    private var observerJob: Job? = null
    private var watchdogJob: Job? = null
    private val maintenance = LogcatRecordingMaintenance(logger, clock)

    private fun capBytes(): Long = settings.state.value.logcatRecordingMaxTotalMb * 1024L * 1024L
    private suspend fun activeFilesSnapshot(): Set<Path> =
        mutex.withLock { autoFiles.values.toSet() + listOfNotNull(manualFile) }

    init {
        // 确保子目录存在（auto/manual）
        runCatching { Files.createDirectories(autoDir()) }
        runCatching { Files.createDirectories(manualDir()) }
        // 启动清理（spec §3.2 + D4/D5）：归位 → 7 天 → 0 字节 → 总量上限；失败仅 WARN，不阻塞
        scope.launch {
            val active = activeFilesSnapshot()
            withContext(io) { runCatching { maintenance.cleanupOnStartup(_recordingsDir.value, capBytes(), active) } }
                .onFailure { logger.warn("recording cleanup failed: ${it.message}") }
        }
        observerJob = scope.launch { observeDevices() }
        watchdogJob = scope.launch { fileWatchdog() }
    }

    suspend fun startManual(serial: String) {
        mutex.withLock {
            if (_manualState.value is ManualRecordState.Recording) return
            // 持锁 getprop（数百 ms）：短暂阻塞 observeDevices 增删 auto recorder，可接受；
            // 换来"断连时 stopManual 等待起录完成再停"的正确顺序（否则 stopManual 会因
            // manualRecorder 尚未就位而 no-op，把录制漏在断连设备上）。
            startManualLocked(serial)
        }
    }

    suspend fun stopManual(reason: String) {
        mutex.withLock {
            val rec = manualRecorder ?: return
            val file = manualFile ?: return
            rec.stop(reason)
            // Recorder stopped; set final state directly from recorder's last known state,
            // then cancel the collector (StateFlow.collect never returns on its own).
            val finalState = rec.state.value
            _manualState.value = ManualRecordState.Stopped(
                file, finalState.lineCount, finalState.bytesWritten, finalState.stopReason
            )
            manualJob?.cancel(); manualJob = null
            manualRecorder = null
            manualFile = null
        }
    }

    /** mutex 持有中调用。watchdog 重启手动会话也走这里。 */
    private suspend fun startManualLocked(serial: String) {
        val label = deviceLabel(serial)
        val file = uniqueFile(manualDir().resolve("${label}_${stamp()}.log"))
        val rec = LogcatRecorder(commands, logger, scope, io, file, rotationBytes)
        manualJob?.cancel()
        manualRecorder = rec
        manualFile = file
        manualJob = scope.launch {
            rec.state.collect { st ->
                // 新 recorder 的初始状态是 STOPPED(0行0字节)——那是"尚未起录"，不是
                // "录制结束"。发布它会让 UI 在起录瞬间看到假 Stopped，"停止后自动弹
                // 定位"被误触发（第二次起录时又弹一次上次的录制位置）。真实的结束
                // 状态（用户停/断连/write_failed）带 stopReason 或进度，不受此过滤影响。
                val bogusInitial = st.status == RecordStatus.STOPPED &&
                    st.stopReason == null && st.error == null &&
                    st.lineCount == 0L && st.bytesWritten == 0L
                if (!bogusInitial) {
                    _manualState.value = when (st.status) {
                        RecordStatus.RECORDING ->
                            ManualRecordState.Recording(serial, file, st.lineCount, st.bytesWritten)
                        RecordStatus.STOPPED ->
                            ManualRecordState.Stopped(file, st.lineCount, st.bytesWritten, st.stopReason)
                    }
                }
            }
        }
        rec.start(serial)
    }

    fun stop() {
        observerJob?.cancel(); observerJob = null
        watchdogJob?.cancel(); watchdogJob = null
        manualJob?.cancel(); manualJob = null
        val rec = manualRecorder
        val file = manualFile
        manualRecorder = null
        manualFile = null
        if (rec != null && file != null) {
            scope.launch {
                rec.stop(null)
                val finalState = rec.state.value
                _manualState.value = ManualRecordState.Stopped(
                    file, finalState.lineCount, finalState.bytesWritten, finalState.stopReason
                )
            }
        }
        scope.launch {
            mutex.withLock {
                val serials = autoRecorders.keys.toList()
                serials.forEach { stopAutoLocked(it) }
            }
        }
    }

    companion object {
        /** 文件存活检测周期（真实时间，跑在 io 上）。 */
        private const val FILE_WATCH_INTERVAL_MS = 5000L

        /** [deviceLabel] 的 getprop 重试：总尝试次数与间隔（虚拟时间下测试可快进）。 */
        private const val LABEL_GETPROP_ATTEMPTS = 3
        private const val LABEL_RETRY_MS = 1000L

        /** serial → 文件名安全段：':'（无线 serial）与 Windows 非法字符统一 '_'（spec §3.2）。 */
        fun sanitizeSerial(serial: String): String =
            serial.replace(":", "_").replace(Regex("""[\\/*?"<>|]"""), "_")

        /** 品牌型号版本串 → 文件名安全段：空格/':'与 Windows 非法字符统一 '_'。 */
        private fun fileSafe(s: String): String = s.replace(Regex("""[\s\\/*?"<>|:]"""), "_")

        private fun stamp(): String =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

        private fun today(): String =
            LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

        private fun uniqueFile(path: Path): Path {
            if (!Files.exists(path)) return path
            var i = 1
            while (true) {
                val candidate = path.resolveSibling(
                    path.fileName.toString().removeSuffix(".log") + "_$i.log"
                )
                if (!Files.exists(candidate)) return candidate
                i++
            }
        }
    }

    // --- observeDevices / startAutoLocked / stopAutoLocked ---

    private fun autoDir(): Path = _recordingsDir.value.resolve("auto")
    private fun manualDir(): Path = _recordingsDir.value.resolve("manual")

    /** 轮转/watchdog 重启用：取目录里最小未用的序号开新文件（spec D3，不追加）。 */
    private fun nextRotationFile(label: String): Path {
        var n = 1
        while (Files.exists(autoDir().resolve("${label}_${today()}_$n.log"))) n++
        return autoDir().resolve("${label}_${today()}_$n.log")
    }

    /** 设备上下线 + 设置开关 → 增删 auto recorder；同时守护手动会话（设备断开即停）。
     *  目录热切换（spec D2）：停全部 → 迁移 → 更新有效目录 → 走下方常规逻辑重启 auto。
     *  上限变更（spec D4）：立即执行一次 enforceSizeCap。 */
    private suspend fun observeDevices() {
        var lastDir: String? = settings.state.value.logcatRecordingsDir
        var lastCap: Int = settings.state.value.logcatRecordingMaxTotalMb
        combine(settings.state, tracker.devices) { s, devs -> s to devs }.collect { (s, devs) ->
            val online = devs.filter { it.status == DeviceStatus.ONLINE }.map { it.serial }.toSet()
            // 目录热切换（spec D2）：停全部 → 迁移 → 更新有效目录 → 走下方常规逻辑重启 auto
            if (s.logcatRecordingsDir != lastDir) {
                lastDir = s.logcatRecordingsDir
                val newDir = effectiveDir(s)
                if (newDir != _recordingsDir.value) {
                    if (_manualState.value is ManualRecordState.Recording) stopManual("recording_dir_changed")
                    mutex.withLock { autoRecorders.keys.toList().forEach { stopAutoLocked(it) } }
                    withContext(io) { runCatching { maintenance.migrateFiles(_recordingsDir.value, newDir) } }
                    _recordingsDir.value = newDir
                    logger.info("recordings dir changed to $newDir (migrated)")
                }
            }
            // 上限变更 → 立即执行一次（spec D4）
            if (s.logcatRecordingMaxTotalMb != lastCap) {
                lastCap = s.logcatRecordingMaxTotalMb
                val active = activeFilesSnapshot()
                withContext(io) { runCatching { maintenance.enforceSizeCap(_recordingsDir.value, capBytes(), active) } }
            }
            val manual = _manualState.value
            if (manual is ManualRecordState.Recording && manual.serial !in online) {
                stopManual("device_disconnected")
            }
            mutex.withLock {
                autoRecorders.keys.filter { it !in online }.forEach { stopAutoLocked(it) }
                if (s.logcatAutoRecord) online.filter { it !in autoRecorders }.forEach { startAutoLocked(it) }
                else autoRecorders.keys.toList().forEach { stopAutoLocked(it) }
            }
        }
    }

    /** mutex 持有中调用。轮转续开：recorder 写满文件原地收尾后，这里换新文件重开（spec §3.2）。
     *  每次起录（含轮转）重新 getprop 取品牌型号——轮转按 N MB 触发，开销可忽略。 */
    internal suspend fun startAutoLocked(serial: String, forceNewFile: Boolean = false) {
        val label = deviceLabel(serial)   // 同 startManual：持锁 getprop 的短暂阻塞可接受
        val file = if (forceNewFile) nextRotationFile(label)
                   else autoDir().resolve("${label}_${today()}.log")
        val append = !forceNewFile && Files.exists(file)
        val rec = LogcatRecorder(commands, logger, scope, io, file, rotationBytes, append)
        autoRecorders[serial] = rec
        autoFiles[serial] = file
        autoJobs[serial] = scope.launch {
            rec.state.collect { st ->
                _autoStates.value = _autoStates.value + (serial to st)
                if (st.rotated) {
                    mutex.withLock {
                        if (autoRecorders[serial] === rec) {
                            // 自取消放在续开之后：已取消的协程里跑挂起代码（deviceLabel 的重试
                            // delay）会当场抛 CancellationException，续开半途夭折。先续开再自断，
                            // 老 recorder 的后续状态同样进不了 UI。
                            val oldJob = autoJobs.remove(serial)
                            startAutoLocked(serial, forceNewFile = true)
                            oldJob?.cancel()
                        }
                    }
                }
            }
        }
        rec.start(serial)
        scope.launch {
            val active = activeFilesSnapshot()
            withContext(io) { runCatching { maintenance.enforceSizeCap(_recordingsDir.value, capBytes(), active) } }
        }
    }

    /** mutex 持有中调用（suspend：内部 join recorder 的收尾）。 */
    internal suspend fun stopAutoLocked(serial: String) {
        autoJobs.remove(serial)?.cancel()
        autoRecorders.remove(serial)?.stop(null)
        autoFiles.remove(serial)
        _autoStates.value = _autoStates.value - serial
    }

    /** 录制目录里 lastModified 最新的 .log 文件（录制页常驻"定位最近录制"按钮用）；
     *  无录制文件返回 null。目录扫描在 [io] 上执行。 */
    suspend fun latestRecordingFile(): Path? = withContext(io) {
        val files = listOf(autoDir(), manualDir()).flatMap { d ->
            d.toFile().listFiles { f -> f.isFile && f.name.endsWith(".log") }?.toList() ?: emptyList()
        }
        files.maxByOrNull { it.lastModified() }?.toPath()
    }

    /** auto/ + manual/ 总占用（设置页展示）。目录扫描在 [io] 上执行。 */
    suspend fun totalRecordingBytes(): Long = withContext(io) { maintenance.totalBytes(_recordingsDir.value) }

    /** serial → 录制文件名前缀段：`品牌_型号_Android版本`（getprop）。起录瞬间设备常未就绪
     * （adb connect 冷启动、断流重连），故失败/unknown 时重试；重试耗尽后回退历史设备别名
     * （auto-name 已持久化到 devices.json），仍无才回退 [sanitizeSerial]——保证起录永不因
     * 取属性失败而失败，且同一设备不会仅因起录竞态就换成 IP 命名（spec §3.2）。 */
    private suspend fun deviceLabel(serial: String): String {
        repeat(LABEL_GETPROP_ATTEMPTS) { attempt ->
            val props = runCatching { commands.deviceProps(serial) }.getOrNull()
            if (props != null && props.brand != "unknown" && props.model != "unknown") {
                val label = fileSafe("${props.brand} ${props.model} Android${props.androidVersion}")
                if (label.isNotBlank()) return label
            }
            if (attempt < LABEL_GETPROP_ATTEMPTS - 1) delay(LABEL_RETRY_MS)
        }
        val alias = runCatching { history.load().firstOrNull { it.serial == serial }?.alias }.getOrNull()
        if (!alias.isNullOrBlank()) {
            val label = fileSafe(alias)
            if (label.isNotBlank()) {
                logger.info("recording label for $serial: getprop unavailable after retries, using history alias \"$alias\"")
                return label
            }
        }
        return sanitizeSerial(serial)
    }

    /** 周期检测活动会话的录制文件是否被用户在外部删除（Windows 下打开中的文件通常删不掉，
     *  但应用关闭后删除/其他平台/目录被删等场景仍会出现"UI 显示后台录制中却没有文件"的失配），
     *  删了就原地重启会话换新文件。delay 在 [io] 上走真实时间（同 LogcatRecorder.flushLoop，
     *  避免给 TestDispatcher 虚拟时间塞进无限自续任务把 advanceUntilIdle 挂死）。 */
    private suspend fun fileWatchdog() {
        withContext(io) {
            while (true) {
                delay(FILE_WATCH_INTERVAL_MS)
                runCatching { restartRecordersWithMissingFiles() }
                    .onFailure { logger.warn("recording file watchdog failed: ${it.message}") }
            }
        }
    }

    /** 文件不存在的活动会话原地重启、换新文件（手动会话重启不产生 Stopped 状态——否则 UI
     *  的"停止后自动弹定位"会误触发）。[exists] 注入以便测试模拟"文件被删"。 */
    internal suspend fun restartRecordersWithMissingFiles(exists: (Path) -> Boolean = { Files.exists(it) }) {
        mutex.withLock {
            val manual = _manualState.value
            if (manual is ManualRecordState.Recording && !exists(manual.file)) {
                logger.info("recording file deleted externally; restarting manual session for ${manual.serial}")
                manualJob?.cancel()   // 先断 collector，recorder 收尾的 STOPPED 不再进 UI 状态
                manualRecorder?.stop(null)
                startManualLocked(manual.serial)
            }
            autoFiles.filterValues { !exists(it) }.keys.forEach { serial ->
                logger.info("recording file deleted externally; restarting auto session for $serial")
                stopAutoLocked(serial)
                startAutoLocked(serial, forceNewFile = true)
            }
        }
    }
}
