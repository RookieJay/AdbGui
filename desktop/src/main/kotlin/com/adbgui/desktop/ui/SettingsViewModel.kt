package com.adbgui.desktop.ui

import com.adbgui.core.log.LogLevel
import com.adbgui.core.domain.ScrcpyLaunchProfile
import com.adbgui.core.settings.Settings
import com.adbgui.core.settings.SettingsStore
import com.adbgui.desktop.ui.i18n.Locale
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val store: SettingsStore, private val scope: CoroutineScope) {
    private val _settings = MutableStateFlow(Settings())
    val settings = _settings.asStateFlow()
    init { scope.launch { _settings.value = store.load() } }

    fun setAdbPath(path: String?) = scope.launch { store.update { it.copy(adbPathOverride = path) }; refresh() }
    fun setLogLevel(level: LogLevel) = scope.launch { store.update { it.copy(logLevel = level) }; refresh() }
    fun setLocale(locale: Locale) = scope.launch {
        store.update { it.copy(locale = locale.code) }
        refresh()
        Strings.set(locale)
    }
    fun setScrcpyLaunch(profile: ScrcpyLaunchProfile) = scope.launch {
        store.update { it.copy(scrcpyLaunch = profile) }
        refresh()
    }
    private suspend fun refresh() { _settings.value = store.load() }
}
