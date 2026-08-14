package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.DeviceProps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.adbgui.desktop.ui.i18n.Strings

class DeviceInfoViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _props = MutableStateFlow<DeviceProps?>(null)
    val props = _props.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()
    private val _exportBusy = MutableStateFlow(false)
    val exportBusy: StateFlow<Boolean> = _exportBusy.asStateFlow()

    fun load() = scope.launch {
        _error.value = null
        val serial = selectedSerial.value
        if (serial == null) { _props.value = null; return@launch }  // clear stale data when no valid device
        try { _props.value = repo.deviceProps(serial) }
        catch (e: AdbCommandException) { _props.value = null; _error.value = e.stderr }  // clear stale on failure
    }

    fun export() = scope.launch {
        _error.value = null
        val serial = selectedSerial.value
        if (serial == null) { _report.value = null; return@launch }
        _exportBusy.value = true
        try {
            val body = repo.deviceDetailReport(serial)
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            // Prepend the page's curated summary (the same DeviceProps shown on screen).
            val summary = _props.value?.let { p ->
                buildString {
                    appendLine(Strings.t("report_summary_header"))
                    appendLine("${Strings.t("prop_model")}: ${p.model}")
                    appendLine("${Strings.t("prop_android_version")}: ${p.androidVersion}")
                    appendLine("${Strings.t("prop_sdk")}: ${p.sdkInt}")
                    appendLine("${Strings.t("prop_serial")}: ${p.serial}")
                    appendLine("${Strings.t("prop_resolution")}: ${p.resolution}")
                    appendLine("${Strings.t("prop_abi")}: ${p.abi}")
                    appendLine()
                }
            } ?: ""
            _report.value = "${Strings.t("report_export_header")}\n${Strings.t("prop_serial")}: $serial\n${Strings.t("report_generated").format(stamp)}\n\n$summary$body"
        } catch (e: Exception) { _error.value = Strings.t("status_export_failed").format(e.message); _report.value = null }
        finally { _exportBusy.value = false }
    }

    init {
        // Auto-refresh when the selected device changes (incl. the first auto-select).
        scope.launch { selectedSerial.collect { load() } }
    }
}
