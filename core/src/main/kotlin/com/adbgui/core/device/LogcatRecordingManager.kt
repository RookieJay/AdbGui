package com.adbgui.core.device

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.log.Logger
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
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
    private val logger: Logger,
    private val scope: CoroutineScope,
    val recordingsDir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rotationBytes: Long = LogcatRecorder.ROTATION_BYTES,
) {
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
    private var observerJob: Job? = null

    fun startManual(serial: String) {
        if (_manualState.value is ManualRecordState.Recording) return
        val file = uniqueFile(recordingsDir.resolve("manual_${sanitizeSerial(serial)}_${stamp()}.log"))
        val rec = LogcatRecorder(commands, logger, scope, io, file, rotationBytes)
        manualJob?.cancel()
        manualRecorder = rec
        manualFile = file
        manualJob = scope.launch {
            rec.state.collect { st ->
                _manualState.value = when (st.status) {
                    RecordStatus.RECORDING ->
                        ManualRecordState.Recording(serial, file, st.lineCount, st.bytesWritten)
                    RecordStatus.STOPPED ->
                        ManualRecordState.Stopped(file, st.lineCount, st.bytesWritten, st.stopReason)
                }
            }
        }
        rec.start(serial)
    }

    suspend fun stopManual(reason: String) {
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

    fun stop() {
        observerJob?.cancel(); observerJob = null
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
        const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** serial → 文件名安全段：':'（无线 serial）与 Windows 非法字符统一 '_'（spec §3.2）。 */
        fun sanitizeSerial(serial: String): String =
            serial.replace(":", "_").replace(Regex("""[\\/*?"<>|]"""), "_")

        private fun stamp(): String =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

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

    // --- Task 4 填充：observeDevices / startAutoLocked / stopAutoLocked / cleanupOldAutoFiles ---
    // 本任务先留空实现，保证编译（autoStates 恒为 emptyMap）：
    internal fun startAutoLocked(serial: String) { /* Task 4 */ }
    internal suspend fun stopAutoLocked(serial: String) { /* Task 4 */ }
    internal fun cleanupOldAutoFiles() { /* Task 4 */ }
}
