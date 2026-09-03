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

    fun setFilters(f: LogcatFilters) = controller.setFilters(f)
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun clear() = controller.clear()
    fun export(): String = controller.export()

    /** Re-enable logd on devices that ship with it silenced (e.g. TCL TVs → empty logcat),
     *  then restart the stream so logs start flowing. Surfaces failure inline via [fixError]. */
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
