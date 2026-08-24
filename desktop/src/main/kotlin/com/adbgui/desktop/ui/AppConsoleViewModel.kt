package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.Extra
import com.adbgui.core.domain.PackageInfo
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppConsoleViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _packages = MutableStateFlow<List<PackageInfo>>(emptyList())
    val packages: StateFlow<List<PackageInfo>> = _packages.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _broadcastResult = MutableStateFlow<String?>(null)
    val broadcastResult: StateFlow<String?> = _broadcastResult.asStateFlow()
    private val _providerResult = MutableStateFlow<String?>(null)
    val providerResult: StateFlow<String?> = _providerResult.asStateFlow()

    fun load() = scope.launch {
        val serial = selectedSerial.value
        if (serial == null) { _packages.value = emptyList(); _busy.value = false; return@launch }
        _busy.value = true; _error.value = null
        try { _packages.value = repo.listPackages(serial) }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    fun install(apkPath: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null; _message.value = null
        try {
            repo.install(serial, apkPath, reinstall = true)
            _message.value = Strings.t("install_success").format(java.io.File(apkPath).name)
            load()
        }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    /** Clear the ephemeral success message (called by the UI after its auto-clear delay). */
    fun clearMessage() { _message.value = null }

    fun uninstall(pkg: String) = scope.launch {
        val serial = selectedSerial.value
        _busy.value = true; _error.value = null
        try { if (serial != null) repo.uninstall(serial, pkg) }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
        load()
    }

    fun clearData(pkg: String) = scope.launch {
        val serial = selectedSerial.value
        _busy.value = true; _error.value = null
        try { if (serial != null) repo.clearData(serial, pkg) }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
        load()
    }

    fun forceStop(pkg: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try { repo.forceStop(serial, pkg) }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    fun startApp(pkg: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try { repo.startApp(serial, pkg) }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    fun startAppActivity(pkg: String, activity: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try { repo.startAppActivity(serial, pkg, activity) }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    fun restart(pkg: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try { repo.forceStop(serial, pkg); repo.startApp(serial, pkg) }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    fun sendBroadcast(action: String, uri: String?, extras: List<Extra>) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null; _broadcastResult.value = null
        try { val out = repo.sendBroadcast(serial, action, uri, extras); _broadcastResult.value = out }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    fun queryProvider(uri: String, where: String?) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null; _providerResult.value = null
        try { val out = repo.queryProvider(serial, uri, where); _providerResult.value = out }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    private val refreshJob: Job = scope.launch { selectedSerial.collect { load() } }
    fun stop() { refreshJob.cancel() }
}
