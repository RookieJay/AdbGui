package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.PackageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppManagerViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _packages = MutableStateFlow<List<PackageInfo>>(emptyList())
    val packages: StateFlow<List<PackageInfo>> = _packages.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun load() = scope.launch {
        val serial = selectedSerial.value
        if (serial == null) { _packages.value = emptyList(); _busy.value = false; return@launch }  // clear stale when no valid device
        _busy.value = true; _error.value = null
        try { _packages.value = repo.listPackages(serial) }
        catch (e: AdbCommandException) { _packages.value = emptyList(); _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }  // clear stale on failure
        finally { _busy.value = false }
    }

    fun install(apkPath: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try { repo.install(serial, apkPath, reinstall = true); load() }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
    }

    fun uninstall(pkg: String) = scope.launch {
        val serial = selectedSerial.value
        _busy.value = true; _error.value = null
        try { if (serial != null) repo.uninstall(serial, pkg) }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
        load()
    }
    fun clearData(pkg: String) = scope.launch {
        val serial = selectedSerial.value
        _busy.value = true; _error.value = null
        try { if (serial != null) repo.clearData(serial, pkg) }
        catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _busy.value = false }
        load()
    }

    // Auto-refresh when the selected device changes (incl. the first auto-select).
    private val refreshJob: Job = scope.launch { selectedSerial.collect { load() } }
    fun stop() { refreshJob.cancel() }
}
