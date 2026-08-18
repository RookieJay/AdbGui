package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.RemoteButton
import com.adbgui.core.settings.Settings
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RemoteViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _customButtons = MutableStateFlow<List<RemoteButton>>(emptyList())
    val customButtons: StateFlow<List<RemoteButton>> = _customButtons.asStateFlow()

    init { scope.launch { _customButtons.value = settingsStore.load().remoteButtons } }

    fun sendKey(keycode: Int) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _error.value = null; _busy.value = true
        try { repo.inputKey(serial, keycode) }
        catch (e: Exception) { _error.value = e.message ?: "unknown error" }
        finally { _busy.value = false }
    }

    fun addButton(label: String, keycode: Int) = scope.launch {
        val btn = RemoteButton(id = "btn_${System.currentTimeMillis()}", label = label, keycode = keycode)
        settingsStore.update { it.copy(remoteButtons = it.remoteButtons + btn) }
        refresh()
    }

    fun updateButton(id: String, label: String, keycode: Int) = scope.launch {
        settingsStore.update { s ->
            s.copy(remoteButtons = s.remoteButtons.map { if (it.id == id) it.copy(label = label, keycode = keycode) else it })
        }
        refresh()
    }

    fun removeButton(id: String) = scope.launch {
        settingsStore.update { s -> s.copy(remoteButtons = s.remoteButtons.filterNot { it.id == id }) }
        refresh()
    }

    private suspend fun refresh() { _customButtons.value = settingsStore.load().remoteButtons }
    fun clearError() { _error.value = null }
}
