package com.adbgui.desktop.ui

import com.adbgui.core.device.LogcatController
import com.adbgui.core.device.LogcatFilters
import com.adbgui.core.device.LogcatStatus
import com.adbgui.core.domain.LogcatLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LogcatViewModel(
    private val controller: LogcatController,
    selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    val lines: StateFlow<List<LogcatLine>> = controller.lines
    val filters: StateFlow<LogcatFilters> = controller.filters
    val status: StateFlow<LogcatStatus> = controller.status
    val error: StateFlow<String?> = controller.error

    fun setFilters(f: LogcatFilters) = controller.setFilters(f)
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun clear() = controller.clear()
    fun export(): String = controller.export()

    private val refreshJob: Job = scope.launch { selectedSerial.collect { it?.let { controller.start(it) } } }
    fun stop() { refreshJob.cancel() }
}
