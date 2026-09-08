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

class SettingsViewModel(private val store: SettingsStore, private val scope: CoroutineScope) {
    val settings: StateFlow<Settings> = store.state
    init { scope.launch { store.load() } }

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
    fun setCheckOnStartup(b: Boolean) = scope.launch {
        store.update { it.copy(update = it.update.copy(checkOnStartup = b)) }
    }
}
