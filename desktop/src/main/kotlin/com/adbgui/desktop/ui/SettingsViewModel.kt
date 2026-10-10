package com.adbgui.desktop.ui

import com.adbgui.core.log.LogLevel
import com.adbgui.core.domain.DeviceGroupBy
import com.adbgui.core.domain.ScrcpyLaunchProfile
import com.adbgui.core.settings.Settings
import com.adbgui.core.settings.SettingsStore
import com.adbgui.desktop.ui.i18n.Locale
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val recording: com.adbgui.core.device.LogcatRecordingManager? = null,
) {
    val settings: StateFlow<Settings> = store.state
    init { scope.launch { store.load() } }

    /** Effective recordings dir from core (handles custom dir + invalid-path fallback). Null when no manager injected (tests). */
    val recordingsDir: java.nio.file.Path? get() = recording?.recordingsDir

    fun setAdbPath(path: String?) = scope.launch { store.update { it.copy(adbPathOverride = path) } }
    fun setScrcpyPath(path: String?) = scope.launch { store.update { it.copy(scrcpyPathOverride = path) } }
    fun setScrcpyMode(mode: String) = scope.launch { store.update { it.copy(scrcpyMode = mode) } }
    fun setLogLevel(level: LogLevel) = scope.launch { store.update { it.copy(logLevel = level) } }
    fun setLocale(locale: Locale) = scope.launch {
        store.update { it.copy(locale = locale.code) }
        Strings.set(locale)
    }
    fun setTheme(code: String) = scope.launch { store.update { it.copy(theme = code) } }
    fun setScrcpyLaunch(profile: ScrcpyLaunchProfile) = scope.launch { store.update { it.copy(scrcpyLaunch = profile) } }
    fun setDeviceGroupBy(mode: DeviceGroupBy) = scope.launch { store.update { it.copy(deviceGroupBy = mode) } }
    fun setLogcatRingCap(cap: Int) = scope.launch { store.update { it.copy(logcatRingCap = cap) } }
    fun setLogcatAutoRecord(b: Boolean) = scope.launch { store.update { it.copy(logcatAutoRecord = b) } }
    fun setLogcatRecordingsDir(path: String?) = scope.launch { store.update { it.copy(logcatRecordingsDir = path) } }
    fun setLogcatRecordingMaxTotalMb(mb: Int) = scope.launch { store.update { it.copy(logcatRecordingMaxTotalMb = mb) } }

    private val _recordingsUsage = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val recordingsUsage: kotlinx.coroutines.flow.StateFlow<Long?> = _recordingsUsage
    fun refreshRecordingsUsage() = scope.launch { _recordingsUsage.value = recording?.totalRecordingBytes() }

    fun setCheckOnStartup(b: Boolean) = scope.launch {
        store.update { it.copy(update = it.update.copy(checkOnStartup = b)) }
    }
}
