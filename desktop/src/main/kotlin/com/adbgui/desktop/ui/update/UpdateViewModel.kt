package com.adbgui.desktop.ui.update

import com.adbgui.core.settings.SettingsStore
import com.adbgui.core.update.UpdateCheckResult
import com.adbgui.core.update.UpdateChecker
import com.adbgui.core.update.UpdateDownloadResult
import com.adbgui.core.update.UpdateDownloader
import com.adbgui.core.update.UpdateManifest
import com.adbgui.core.update.UpdateSource
import com.adbgui.core.update.UpdateSourceRegistry
import com.adbgui.desktop.platform.MsiUpgrader
import com.adbgui.desktop.platform.PortableUpdateNotifier
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.system.exitProcess

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object NoUpdate : UpdateState()
    data class Available(val manifest: UpdateManifest) : UpdateState()
    data class Downloading(val progress: Float) : UpdateState()
    data class Ready(val msiPath: String, val manifest: UpdateManifest) : UpdateState()
    object Installing : UpdateState()
    data class Error(val message: String, val raw: String? = null) : UpdateState()
}

class UpdateViewModel(
    private val checker: UpdateChecker,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val downloader: UpdateDownloader,
    private val msiUpgrader: MsiUpgrader,
    private val notifier: PortableUpdateNotifier,
    private val exit: (Int) -> Nothing = ::exitProcess,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    @Volatile private var checkJob: Job? = null
    @Volatile private var downloadJob: Job? = null
    @Volatile private var lastManifest: UpdateManifest? = null

    fun checkForUpdates(): Job {
        checkJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
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
                // Resume Ready state if a downloaded MSI for this exact version is still on disk
                // and its sha256 matches (downloaded-but-not-installed, app restarted).
                val ready = settings.update
                val resumed = ready.readyVersion == m.version
                    && ready.readyMsiPath != null
                    && ready.readySha256 == m.sha256
                    && fileSha256Matches(ready.readyMsiPath!!, m.sha256)
                _state.value = if (resumed) UpdateState.Ready(ready.readyMsiPath!!, m) else UpdateState.Available(m)
                persistResult(nowIso, null)
            }
            is UpdateCheckResult.Error -> {
                _state.value = UpdateState.Error(result.message, result.raw)
                persistResult(nowIso, result.message)
            }
        }
    }.also { checkJob = it }
    }

    fun selectSource(id: String) = scope.launch {
        store.update { it.copy(update = it.update.copy(sourceId = id)) }
    }

    fun downloadUpdate(): Job {
        downloadJob?.takeIf { it.isActive }?.let { return it }
        val m = (_state.value as? UpdateState.Available)?.manifest
            ?: return Job().apply { complete() }
        return scope.launch {
            _state.value = UpdateState.Downloading(-1f)  // indeterminate until first byte arrives (gh-proxy may buffer large files before streaming)
            val source = UpdateSourceRegistry.byId(store.load().update.sourceId) ?: UpdateSourceRegistry.default
            val effectiveUrl = effectiveDownloadUrl(source, m)
            val result = try {
                downloader.download(effectiveUrl, m.sha256) { p ->
                    _state.value = UpdateState.Downloading(p)
                }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(m)
                throw e
            }
            when (result) {
                is UpdateDownloadResult.Success -> {
                    _state.value = UpdateState.Ready(result.msiPath, m)
                    store.update { it.copy(update = it.update.copy(
                        readyMsiPath = result.msiPath, readyVersion = m.version, readySha256 = m.sha256)) }
                }
                is UpdateDownloadResult.HashMismatch -> _state.value = UpdateState.Error(Strings.t("update_hash_error"))
                is UpdateDownloadResult.NetworkError -> _state.value = UpdateState.Error(Strings.t("update_download_error").format(result.message ?: "unknown"))
                is UpdateDownloadResult.Cancelled -> _state.value = UpdateState.Available(m)
            }
        }.also { downloadJob = it }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun installNow() {
        val s = _state.value
        if (s !is UpdateState.Ready) return
        _state.value = UpdateState.Installing
        // Clear persisted Ready state — the install is launching; on next launch (post-upgrade)
        // the check will be NoUpdate, and a stale ready entry shouldn't linger.
        scope.launch { store.update { it.copy(update = it.update.copy(readyMsiPath = null, readyVersion = null, readySha256 = null)) } }
        try {
            msiUpgrader.launch(s.msiPath)
        } catch (t: Throwable) {
            _state.value = UpdateState.Error(t.message ?: "install failed")
            return
        }
        exit(0)
    }

    fun openDownloadPage(): Job = scope.launch {
        val s = _state.value
        if (s !is UpdateState.Available && s !is UpdateState.Ready) return@launch
        val m = lastManifest ?: return@launch
        val source = UpdateSourceRegistry.byId(store.load().update.sourceId) ?: UpdateSourceRegistry.default
        notifier.openDownloadPage(effectiveDownloadUrl(source, m))
    }

    fun dismissCurrentUpdate(): Job = scope.launch {
        val s = _state.value as? UpdateState.Available ?: return@launch
        store.update { it.copy(update = it.update.copy(dismissedVersion = s.manifest.version)) }
    }

    private suspend fun persistResult(at: String, err: String?) {
        store.update { it.copy(update = it.update.copy(lastCheckAt = at, lastCheckError = err)) }
    }

    private fun effectiveDownloadUrl(source: UpdateSource, m: UpdateManifest): String =
        source.proxyPrefix?.let { it + m.url } ?: m.url

    /** True if the file at [path] exists and its SHA-256 equals [expected] (lowercase hex). */
    private suspend fun fileSha256Matches(path: String, expected: String): Boolean = withContext(io) {
        val file = java.io.File(path)
        if (!file.isFile) return@withContext false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }
}
