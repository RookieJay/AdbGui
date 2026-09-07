package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.DumpsysPackage
import com.adbgui.core.domain.Extra
import com.adbgui.core.domain.InstallFlags
import com.adbgui.core.domain.PackageDetail
import com.adbgui.core.domain.PackageInfo
import com.adbgui.core.domain.PermissionInfo
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
    private val _detail = MutableStateFlow<PackageDetail?>(null)
    val detail: StateFlow<PackageDetail?> = _detail.asStateFlow()
    private val _detailBusy = MutableStateFlow(false)
    val detailBusy: StateFlow<Boolean> = _detailBusy.asStateFlow()
    private val _detailError = MutableStateFlow<String?>(null)
    val detailError: StateFlow<String?> = _detailError.asStateFlow()
    private val _permissions = MutableStateFlow<List<PermissionInfo>>(emptyList())
    val permissions: StateFlow<List<PermissionInfo>> = _permissions.asStateFlow()
    private val _permissionsBusy = MutableStateFlow(false)
    val permissionsBusy: StateFlow<Boolean> = _permissionsBusy.asStateFlow()
    private val _permissionsError = MutableStateFlow<String?>(null)
    val permissionsError: StateFlow<String?> = _permissionsError.asStateFlow()
    private val _cachedDumpsys = MutableStateFlow<Pair<String, DumpsysPackage>?>(null)  // (pkg, parsed)

    fun load() = scope.launch {
        val serial = selectedSerial.value
        if (serial == null) { _packages.value = emptyList(); _busy.value = false; return@launch }
        _busy.value = true; _error.value = null
        try { _packages.value = repo.listPackages(serial) }
        catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
        finally { _busy.value = false }
    }

    fun install(paths: List<String>, flags: InstallFlags) = scope.launch {
        require(paths.isNotEmpty()) { "install: no paths" }
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null; _message.value = null
        try {
            repo.install(serial, paths, flags)
            _message.value = Strings.t("install_success").format(paths.joinToString(", "))
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

    fun startActivity(action: String?, data: String?, component: String?, extras: List<Extra>) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        val a = action?.trim()?.ifBlank { null }
        val c = component?.trim()?.ifBlank { null }
        if (a == null && c == null) return@launch  // guard: at least one
        _busy.value = true; _error.value = null; _message.value = null
        try {
            val out = repo.startActivity(serial, a, data?.ifBlank { null }, c, extras).trim()
            _message.value = out.ifBlank { Strings.t("start_activity_done") }
        } catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
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

    fun loadDetail(pkg: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _detailBusy.value = true; _detailError.value = null
        try {
            val dp = cachedOrLoad(serial, pkg)
            val libDir = dp.nativeLibraryDir
            val libs = if (libDir != null) repo.listNativeLibs(serial, libDir) else emptyList()
            _detail.value = PackageDetail(
                versionName = dp.versionName, versionCode = dp.versionCode,
                codePath = dp.codePath, publicSourceDir = dp.publicSourceDir,
                nativeLibraryDir = dp.nativeLibraryDir, primaryCpuAbi = dp.primaryCpuAbi,
                nativeLibs = libs,
            )
        } catch (e: AdbCommandException) { _detailError.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _detailBusy.value = false }
    }

    fun loadPermissions(pkg: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _permissionsBusy.value = true; _permissionsError.value = null
        try { _permissions.value = cachedOrLoad(serial, pkg).permissions }
        catch (e: AdbCommandException) { _permissionsError.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _permissionsBusy.value = false }
    }

    fun togglePermission(pkg: String, perm: String, grant: Boolean) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        try {
            if (grant) repo.grant(serial, pkg, perm) else repo.revoke(serial, pkg, perm)
            _permissions.value = _permissions.value.map { if (it.name == perm) it.copy(granted = grant) else it }
            _cachedDumpsys.value?.let { (p, dp) ->
                if (p == pkg) _cachedDumpsys.value = pkg to dp.copy(
                    permissions = dp.permissions.map { if (it.name == perm) it.copy(granted = grant) else it }
                )
            }
        } catch (e: AdbCommandException) { _permissionsError.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
    }

    fun clearDetail() {
        _detail.value = null; _detailError.value = null
        _permissions.value = emptyList(); _permissionsError.value = null
        _cachedDumpsys.value = null
    }

    private suspend fun cachedOrLoad(serial: String, pkg: String): DumpsysPackage {
        _cachedDumpsys.value?.let { (p, dp) -> if (p == pkg) return dp }
        val dp = repo.dumpsysPackage(serial, pkg)
        _cachedDumpsys.value = pkg to dp
        return dp
    }

    private val refreshJob: Job = scope.launch { selectedSerial.collect { clearDetail(); load() } }
    fun stop() { refreshJob.cancel() }
}
