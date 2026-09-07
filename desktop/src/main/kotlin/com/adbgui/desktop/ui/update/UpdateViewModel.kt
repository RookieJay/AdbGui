package com.adbgui.desktop.ui.update

import com.adbgui.core.settings.SettingsStore
import com.adbgui.core.update.UpdateCheckResult
import com.adbgui.core.update.UpdateChecker
import com.adbgui.core.update.UpdateSourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object NoUpdate : UpdateState()
    data class Available(val version: String, val notes: String?, val url: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateViewModel(
    private val checker: UpdateChecker,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    fun checkForUpdates() = scope.launch {
        _state.value = UpdateState.Checking
        val settings = store.load()
        val source = UpdateSourceRegistry.byId(settings.update.sourceId) ?: UpdateSourceRegistry.default
        val result = checker.check(source)
        val nowIso = java.time.Instant.now().toString()
        when (result) {
            is UpdateCheckResult.NoUpdate -> {
                _state.value = UpdateState.NoUpdate
                persistResult(settings.update.sourceId, nowIso, null)
            }
            is UpdateCheckResult.UpdateAvailable -> {
                val m = result.manifest
                _state.value = UpdateState.Available(m.version, m.notes, m.url)
                persistResult(settings.update.sourceId, nowIso, null)
            }
            is UpdateCheckResult.Error -> {
                _state.value = UpdateState.Error(result.message)
                persistResult(settings.update.sourceId, nowIso, result.message)
            }
        }
    }

    fun selectSource(id: String) = scope.launch {
        store.update { it.copy(update = it.update.copy(sourceId = id)) }
    }

    private suspend fun persistResult(sourceId: String, at: String, err: String?) {
        store.update { it.copy(update = it.update.copy(lastCheckAt = at, lastCheckError = err)) }
    }
}
