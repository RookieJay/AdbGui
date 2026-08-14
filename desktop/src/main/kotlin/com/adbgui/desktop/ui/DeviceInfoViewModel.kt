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
        _error.value = null
        val serial = selectedSerial.value
        if (serial == null) { _props.value = null; return@launch }  // clear stale data when no valid device
        try { _props.value = repo.deviceProps(serial) }
        catch (e: AdbCommandException) { _props.value = null; _error.value = e.stderr }  // clear stale on failure
    }

    init {
        // Auto-refresh when the selected device changes (incl. the first auto-select).
        scope.launch { selectedSerial.collect { load() } }
    }
}
