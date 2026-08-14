package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbNotFoundException
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.settings.SettingsStore

interface BundledAdbProvider { fun bundledAdbPath(): String? }
interface PathProbe { fun existsOnPath(name: String): String? }

class AdbLocator(
    private val settings: SettingsStore,
    private val bundled: BundledAdbProvider,
    private val pathProbe: PathProbe,
) {
    suspend fun locate(): AdbBinary {
        settings.load().adbPathOverride?.takeIf { it.isNotBlank() }?.let { return AdbBinary(it, AdbSource.OVERRIDE) }
        bundled.bundledAdbPath()?.let { return AdbBinary(it, AdbSource.BUNDLED) }
        pathProbe.existsOnPath("adb")?.let { return AdbBinary(it, AdbSource.PATH) }
        throw AdbNotFoundException("adb not found. Open Settings and set an adb path.")
    }
}
