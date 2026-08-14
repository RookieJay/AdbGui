package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.DeviceProps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceInfoViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _props = MutableStateFlow<DeviceProps?>(null)
    val props = _props.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun load() = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        try { _props.value = repo.deviceProps(serial) } catch (e: AdbCommandException) { _error.value = e.stderr }
    }
}
