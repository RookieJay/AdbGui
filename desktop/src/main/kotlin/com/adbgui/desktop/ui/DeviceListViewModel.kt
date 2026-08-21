package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.ConnectResult
import com.adbgui.core.domain.DeviceView
import com.adbgui.core.domain.PairResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceListViewModel(private val repo: DeviceRepository, private val scope: CoroutineScope) {
    val devices: StateFlow<List<DeviceView>> = repo.devices
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun connect(ip: String, port: Int, onResult: (ConnectResult) -> Unit = {}) {
        scope.launch {
            _error.value = null
            _busy.value = true
            try {
                val r = repo.connectWireless(ip, port)
                if (!r.success) _error.value = r.message
                onResult(r)
            } finally { _busy.value = false }
        }
    }

    fun disconnect(target: String) { scope.launch { repo.disconnect(target) } }

    fun reconnect(ip: String, port: Int) {
        scope.launch {
            _busy.value = true
            try {
                val r = repo.connectWireless(ip, port)
                if (!r.success) _error.value = r.message
            } finally { _busy.value = false }
        }
    }

    fun setAlias(serial: String, alias: String?) { scope.launch { repo.setAlias(serial, alias) } }
    fun forget(serial: String) { scope.launch { repo.forgetDevice(serial) } }
    fun clearError() { _error.value = null }

    fun pair(ip: String, port: Int, code: String, onResult: (PairResult) -> Unit = {}) {
        scope.launch {
            _error.value = null
            _busy.value = true
            try {
                val pr = repo.pair(ip, port, code)
                // adb pair only registers the key. The pairing port is single-use and closes
                // right after pairing succeeds; connect must use the *connect* port shown on
                // the device's main "Wireless debugging" screen, which may differ. So the
                // UI drives a second connect step with a user-entered connect port — do NOT
                // auto-connect here (it would hit the now-closed pairing port and fail with
                // "protocol fault (couldn't read status message)").
                if (!pr.success) _error.value = pr.message
                onResult(pr)
            } finally { _busy.value = false }
        }
    }
}
