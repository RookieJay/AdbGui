package com.adbgui.desktop.ui.update

import com.adbgui.core.settings.SettingsStore
import com.adbgui.core.update.UpdateCheckResult
import com.adbgui.core.update.UpdateChecker
import com.adbgui.core.update.UpdateDownloadResult
import com.adbgui.core.update.UpdateDownloader
import com.adbgui.core.update.UpdateManifest
import com.adbgui.core.update.UpdateSourceRegistry
import com.adbgui.desktop.platform.MsiUpgrader
import com.adbgui.desktop.platform.PortableUpdateNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object NoUpdate : UpdateState()
    data class Available(val manifest: UpdateManifest) : UpdateState()
    data class Downloading(val progress: Float) : UpdateState()
    data class Ready(val msiPath: String, val manifest: UpdateManifest) : UpdateState()
    object Installing : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateViewModel(
    private val checker: UpdateChecker,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val downloader: UpdateDownloader,
    private val msiUpgrader: MsiUpgrader,
    private val notifier: PortableUpdateNotifier,
    private val exit: (Int) -> Nothing = ::exitProcess,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    @Volatile private var downloadJob: Job? = null
    @Volatile private var lastManifest: UpdateManifest? = null

    fun checkForUpdates() = scope.launch {
        _state.value = UpdateState.Checking
        val settings = store.load()
        val source = UpdateSourceRegistry.byId(settings.update.sourceId) ?: UpdateSourceRegistry.default
        val result = checker.check(source)
        val nowIso = java.time.Instant.now().toString()
        when (result) {
            is UpdateCheckResult.NoUpdate -> {
                _state.value = UpdateState.NoUpdate
                persistResult(nowIso, null)
            }
            is UpdateCheckResult.UpdateAvailable -> {
                val m = result.manifest
                lastManifest = m
                _state.value = UpdateState.Available(m)
                persistResult(nowIso, null)
            }
            is UpdateCheckResult.Error -> {
                _state.value = UpdateState.Error(result.message)
                persistResult(nowIso, result.message)
            }
        }
    }

    fun selectSource(id: String) = scope.launch {
        store.update { it.copy(update = it.update.copy(sourceId = id)) }
    }

    fun downloadUpdate(): Job {
        val m = (_state.value as? UpdateState.Available)?.manifest
            ?: return Job().apply { complete() }
        return scope.launch {
            _state.value = UpdateState.Downloading(0f)
            val result = try {
                downloader.download(m.url, m.sha256) { p ->
                    _state.value = UpdateState.Downloading(p)
                }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(m)
                throw e
            }
            when (result) {
                is UpdateDownloadResult.Success -> _state.value = UpdateState.Ready(result.msiPath, m)
                is UpdateDownloadResult.HashMismatch -> _state.value = UpdateState.Error("sha256 校验失败")
                is UpdateDownloadResult.NetworkError -> _state.value = UpdateState.Error(result.message ?: "unknown")
                is UpdateDownloadResult.Cancelled -> _state.value = UpdateState.Available(m)
            }
        }.also { downloadJob = it }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun installNow() {
        val state = _state.value
        if (state is UpdateState.Ready) {
            _state.value = UpdateState.Installing
            msiUpgrader.launch(state.msiPath)
            exit(0)
        }
    }

    fun openDownloadPage() {
        val s = _state.value
        if (s !is UpdateState.Available && s !is UpdateState.Ready) return
        val m = lastManifest ?: return
        notifier.openDownloadPage(m.url)
    }

    private suspend fun persistResult(at: String, err: String?) {
        store.update { it.copy(update = it.update.copy(lastCheckAt = at, lastCheckError = err)) }
    }
}
