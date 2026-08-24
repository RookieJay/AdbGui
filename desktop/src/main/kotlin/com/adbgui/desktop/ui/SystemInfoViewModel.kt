package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.PackageInfo
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SystemInfoViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    val commands: List<InfoCommand> = systemInfoCommands

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _currentCommand = MutableStateFlow<InfoCommand?>(null)
    val currentCommand: StateFlow<InfoCommand?> = _currentCommand.asStateFlow()

    private val _packages = MutableStateFlow<List<PackageInfo>>(emptyList())
    val packages: StateFlow<List<PackageInfo>> = _packages.asStateFlow()
    private val _selectedPackage = MutableStateFlow<String?>(null)
    val selectedPackage: StateFlow<String?> = _selectedPackage.asStateFlow()
    private val _packagesBusy = MutableStateFlow(false)
    val packagesBusy: StateFlow<Boolean> = _packagesBusy.asStateFlow()
    private val _packagesError = MutableStateFlow<String?>(null)
    val packagesError: StateFlow<String?> = _packagesError.asStateFlow()

    fun selectPackage(pkg: String?) { _selectedPackage.value = pkg }

    fun loadPackages(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _packagesBusy.value = true; _packagesError.value = null
        try {
            _packages.value = repo.listPackages(serial)
        } catch (e: Exception) {
            _packagesError.value = if (e is AdbCommandException)
                "${e.message}\n--- adb stderr ---\n${e.stderr}" else Strings.t("si_packages_failed").format(e.message ?: "")
        } finally { _packagesBusy.value = false }
    }

    fun runCommand(cmd: InfoCommand): Job = scope.launch {
        val serial = selectedSerial.value
        if (serial == null) return@launch  // AppShell hides this page when no device; defensive
        val template = cmd.cmd
        val finalCmd = if (cmd.needsPackage) {
            val pkg = _selectedPackage.value
            if (pkg.isNullOrBlank()) {
                _result.value = null
                _error.value = Strings.t("si_need_package")
                return@launch
            }
            if (!PKG_REGEX.matches(pkg)) {
                _result.value = null
                _error.value = Strings.t("si_invalid_package").format(pkg)
                return@launch
            }
            template.replace("{pkg}", pkg)
        } else template
        _busy.value = true; _error.value = null; _currentCommand.value = cmd
        try {
            _result.value = repo.runShellCmd(serial, finalCmd)
        } catch (e: Exception) {
            _result.value = null
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
        } finally { _busy.value = false }
    }

    private companion object {
        // Android package names: [A-Za-z0-9._]+. Guards against shell metachar injection from a
        // (defensively untrusted) package string. Packages normally come from the device's own
        // `pm list packages -3`, which only ever returns valid names.
        val PKG_REGEX = Regex("^[A-Za-z0-9._]+$")
    }
}
