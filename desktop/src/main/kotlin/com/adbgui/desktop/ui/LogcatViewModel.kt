package com.adbgui.desktop.ui

import com.adbgui.core.device.LogcatController
import com.adbgui.core.device.LogcatFilters
import com.adbgui.core.device.LogcatStatus
import com.adbgui.core.domain.LogcatLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.FileWriter

class LogcatViewModel(
    private val controller: LogcatController,
    private val recording: com.adbgui.core.device.LogcatRecordingManager,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    /** Whether the currently selected device is ONLINE. The empty-state "fix logcat" hint is
     *  gated on this — a disconnected/offline device produces an empty logcat stream too, but
     *  that's not a silenced logd; showing the fix there is misleading. */
    val deviceOnline: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    val lines: StateFlow<List<LogcatLine>> = controller.lines
    val filters: StateFlow<LogcatFilters> = controller.filters
    val status: StateFlow<LogcatStatus> = controller.status
    val error: StateFlow<String?> = controller.error

    // "Fix logcat" action state — separate from the stream's own error (which is about the
    // stream; this is about the one-shot logd-revive command). Null = no error / not attempted.
    private val _fixing = MutableStateFlow(false)
    val fixing: StateFlow<Boolean> = _fixing.asStateFlow()
    private val _fixError = MutableStateFlow<String?>(null)
    val fixError: StateFlow<String?> = _fixError.asStateFlow()

    // Full-buffer export via `adb logcat -d` (streamed to file, not bounded by ringCap).
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()
    private val _exportProgress = MutableStateFlow(0L)
    val exportProgress: StateFlow<Long> = _exportProgress.asStateFlow()
    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()
    private val _savedPath = MutableStateFlow<String?>(null)
    val savedPath: StateFlow<String?> = _savedPath.asStateFlow()

    // --- recording (spec §3.4): forwards only, no adb access (红线 #2) ---
    val manualRecord: StateFlow<com.adbgui.core.device.ManualRecordState> = recording.manualState
    val autoRecordStates: StateFlow<Map<String, com.adbgui.core.device.RecordState>> = recording.autoStates
    val recordingsDir: java.nio.file.Path get() = recording.recordingsDir

    /** Recording→Stopped 的边沿事件：一次手动录制**真正结束**时发一次（该录制的文件）。
     *  UI 的"停止后自动弹定位"只认这个沿——起录新会话、页面重进（残留的 Stopped 态）
     *  都不会重弹。冷 Flow：每个 collector 各自从当前值开始（prev=null → 首值不发）。 */
    val manualStoppedFile: kotlinx.coroutines.flow.Flow<java.nio.file.Path> =
        kotlinx.coroutines.flow.flow {
            var prev: com.adbgui.core.device.ManualRecordState? = null
            recording.manualState.collect { st ->
                if (prev is com.adbgui.core.device.ManualRecordState.Recording &&
                    st is com.adbgui.core.device.ManualRecordState.Stopped
                ) emit(st.file)
                prev = st
            }
        }

    private val _recordError = MutableStateFlow<String?>(null)
    val recordError: StateFlow<String?> = _recordError.asStateFlow()

    /** 常驻定位按钮的目标：最新录制文件，**点击时实时取**（不缓存）——外部删除/新增文件
     *  立刻可见。null = 目录空，UI 转而打开录制目录。 */
    suspend fun latestRecordingFile(): java.nio.file.Path? = recording.latestRecordingFile()

    fun startRecording() {
        val serial = selectedSerial.value
        if (serial == null) {
            _recordError.value = com.adbgui.desktop.ui.i18n.Strings.t("no_device_selected_record")
            return
        }
        _recordError.value = null
        // startManual 是 suspend（起录前先 getprop 取品牌型号命名文件）
        scope.launch { recording.startManual(serial) }
    }

    fun stopRecording() { scope.launch { recording.stopManual("user") } }

    fun setFilters(f: LogcatFilters) = controller.setFilters(f)
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun clear() = controller.clear()
    /** In-memory filtered ring (what the live view shows) — used by the Copy button. */
    fun export(): String = controller.export()

    /** Full-buffer export: `adb logcat -d` dumps everything the device currently retains in its
     *  logd ring (NOT bounded by our in-memory [LogcatController] ringCap), applies the current
     *  filters, and writes matching raw lines to [targetPath] as they arrive. Progress tracked
     *  via [exportProgress]; failure surfaced inline via [exportError]. No-op with an error
     *  when no device is selected. */
    fun exportFull(targetPath: String) {
        val serial = selectedSerial.value
        if (serial == null) {
            _exportError.value = com.adbgui.desktop.ui.i18n.Strings.t("no_device_selected_logcat")
            return
        }
        scope.launch {
            _exporting.value = true
            _exportProgress.value = 0
            _exportError.value = null
            runCatching {
                BufferedWriter(FileWriter(targetPath)).use { w ->
                    controller.dumpLogcat(serial, filters.value) { raw ->
                        w.appendLine(raw)
                        _exportProgress.value++
                    }
                }
            }.onSuccess {
                _savedPath.value = targetPath
            }.onFailure { e ->
                _exportError.value = e.message ?: e.javaClass.simpleName
            }.also {
                _exporting.value = false
            }
        }
    }

    /** Re-enable logd on devices that ship with it silenced (e.g. TCL TVs → empty logcat),
     * then restart the stream so logs start flowing. Surfaces failure inline via [fixError]. */
    fun fixLogcat() {
        val serial = selectedSerial.value ?: return
        scope.launch {
            _fixing.value = true
            _fixError.value = null
            try {
                controller.fixLogcatDisabled(serial)
                controller.start(serial) // logd restart breaks the old stream — reconnect.
            } catch (e: Throwable) {
                _fixError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _fixing.value = false
            }
        }
    }

    private var pageJob: Job? = null

    /** 由 [LogcatScreen] 的 LaunchedEffect(Unit) 调用——页面可见时才挂 collector。
     *  `selectedSerial` 是 StateFlow，collect 会立即发出当前值，所以进入页面即对当前
     *  设备起流，不需要额外补一次调用。离开页面不停流（保留环形缓冲历史）。 */
    fun onPageEntered() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch {
            selectedSerial.collect { serial ->
                if (serial != null) controller.start(serial) else controller.stop()
            }
        }
    }

    fun stop() { pageJob?.cancel(); pageJob = null }
}
