package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScreenshotViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val _image = MutableStateFlow<ByteArray?>(null)
    val image = _image.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _captureDone = MutableStateFlow(0L)
    /** Increments each time [capture] finishes (success or failure). Lets the UI open the
     *  screenshot window only after a shot is ready, instead of on click. */
    val captureDone = _captureDone.asStateFlow()

    fun capture() = scope.launch {
        val seq = _captureDone.value + 1
        val serial = selectedSerial.value
        logger.info("[screenshot] capture start seq=$seq serial=${serial ?: "null"}")
        try {
            _error.value = null
            if (serial != null) {
                try {
                    val bytes = repo.screenshot(serial)
                    _image.value = bytes
                    logger.info("[screenshot] captured seq=$seq bytes=${bytes.size}")
                } catch (e: Exception) {
                    _error.value = e.message
                    logger.warn("[screenshot] capture failed seq=$seq: ${e.message}", e)
                }
            } else {
                logger.warn("[screenshot] no serial selected seq=$seq")
            }
        } finally {
            _captureDone.value++
            logger.info("[screenshot] capture done seq=$seq captureDone=${_captureDone.value}")
        }
    }
}
