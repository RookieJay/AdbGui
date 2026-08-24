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
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun reboot(mode: RebootMode) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null; _message.value = null
        try { val out = repo.reboot(serial, mode); _message.value = out.ifBlank { null } }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
    }

    fun root() = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null; _message.value = null
        try { classifyRootRemount(repo.root(serial)) }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
    }

    fun remount() = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null; _message.value = null
        try { classifyRootRemount(repo.remount(serial)) }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
    }

    /** `adb root` / `adb remount` exit 0 even when the device refuses (production builds) — the stdout
     *  IS the result. Route the known refusal patterns (recorded from a real VIDAA production device:
     *  "adbd cannot run as root in production builds", "Not running as root. Try adb root first.") to
     *  _error (red) so a refusal isn't shown as green success; genuine success → _message (green). */
    private fun classifyRootRemount(out: String) {
        val lo = out.lowercase()
        val refused = lo.contains("cannot run as root") || lo.contains("not running as root") || lo.contains("permission denied")
        if (refused) _error.value = out.trim().ifBlank { "refused" }
        else _message.value = out.ifBlank { null }
    }

    fun clearError() { _error.value = null }
}
