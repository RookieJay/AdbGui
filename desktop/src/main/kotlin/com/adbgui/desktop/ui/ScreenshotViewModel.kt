package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScreenshotViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _image = MutableStateFlow<ByteArray?>(null)
    val image = _image.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun capture() = scope.launch {
        _error.value = null
        val serial = selectedSerial.value ?: return@launch
        try { _image.value = repo.screenshot(serial) } catch (e: Exception) { _error.value = e.message }
    }
}
