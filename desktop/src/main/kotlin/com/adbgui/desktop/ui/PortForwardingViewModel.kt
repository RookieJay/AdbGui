package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardEntry
import com.adbgui.core.domain.ForwardSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Thin VM over [DeviceRepository] for the Port Forwarding page. Reads selectedSerial, loads
 *  this device's forwards, and forwards add/remove actions. Errors are surfaced inline (no modal),
 *  and the list is always refreshed after a mutating action so the UI reflects adb's real state. */
class PortForwardingViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _forwards = MutableStateFlow<List<ForwardEntry>>(emptyList())
    val forwards: StateFlow<List<ForwardEntry>> = _forwards.asStateFlow()

    private val _localType = MutableStateFlow(ForwardEndpointType.TCP)
    val localType: StateFlow<ForwardEndpointType> = _localType.asStateFlow()
    private val _localValue = MutableStateFlow("")
    val localValue: StateFlow<String> = _localValue.asStateFlow()
    private val _remoteType = MutableStateFlow(ForwardEndpointType.LOCALABSTRACT)
    val remoteType: StateFlow<ForwardEndpointType> = _remoteType.asStateFlow()
    private val _remoteValue = MutableStateFlow("")
    val remoteValue: StateFlow<String> = _remoteValue.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // One collector for the lifetime of the VM: re-load whenever the selected device changes.
    // `collectLatest` cancels the previous emission's work — so `doRefresh` runs as a cancellable
    // child of the current emission, NOT as a detached `scope.launch` job. This prevents a stale
    // refresh from a previous device overwriting the list after a rapid A→B switch (I-1).
    private val collector: Job = scope.launch {
        selectedSerial.collectLatest { serial ->
            if (serial != null) doRefresh(serial) else _forwards.value = emptyList()
        }
    }

    fun stop() = collector.cancel()

    fun setLocalType(t: ForwardEndpointType) { _localType.value = t }
    fun setLocalValue(v: String) { _localValue.value = v }
    fun setRemoteType(t: ForwardEndpointType) { _remoteType.value = t }
    fun setRemoteValue(v: String) { _remoteValue.value = v }

    fun clearError() { _error.value = null }

    fun refresh(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        doRefresh(serial)
    }

    private suspend fun doRefresh(serial: String) {
        _busy.value = true
        try {
            _forwards.value = repo.listForwards(serial)
        } catch (e: Exception) {
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
        } finally { _busy.value = false }
    }

    fun add(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        val local = _localValue.value.trim()
        val remote = _remoteValue.value.trim()
        if (local.isEmpty() || remote.isEmpty()) {
            _error.value = com.adbgui.desktop.ui.i18n.Strings.t("pf_need_both_specs")
            return@launch
        }
        _busy.value = true; _error.value = null
        try {
            repo.forward(serial, ForwardSpec(_localType.value, local), ForwardSpec(_remoteType.value, remote))
            _forwards.value = repo.listForwards(serial)
        } catch (e: Exception) {
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
            // Still refresh so the list reflects adb's real state (the failed add may have left nothing).
            runCatching { _forwards.value = repo.listForwards(serial) }
        } finally { _busy.value = false }
    }

    fun remove(local: ForwardSpec): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            repo.removeForward(serial, local)
            _forwards.value = repo.listForwards(serial)
        } catch (e: Exception) {
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
            runCatching { _forwards.value = repo.listForwards(serial) }
        } finally { _busy.value = false }
    }

    fun removeAll(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            repo.removeAllForwards(serial)
            _forwards.value = repo.listForwards(serial)
        } catch (e: Exception) {
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
            runCatching { _forwards.value = repo.listForwards(serial) }
        } finally { _busy.value = false }
    }
}
