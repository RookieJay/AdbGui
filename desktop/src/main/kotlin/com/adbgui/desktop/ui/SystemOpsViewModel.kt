package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.RebootMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SystemOpsViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun reboot(mode: RebootMode) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try { repo.reboot(serial, mode) }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
    }

    fun root() = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try { repo.root(serial) }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
    }

    fun remount() = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try { repo.remount(serial) }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
    }

    fun clearError() { _error.value = null }
}
