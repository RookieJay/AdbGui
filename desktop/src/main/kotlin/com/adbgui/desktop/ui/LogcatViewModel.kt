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

    private val refreshJob: Job = scope.launch { selectedSerial.collect { it?.let { controller.start(it) } } }
    fun stop() { refreshJob.cancel() }
}
