package com.adbgui.core.adb

import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbNotFoundException
import com.adbgui.core.settings.Settings
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdbLocatorTest {
    private class FakeBundled(var path: String?) : BundledAdbProvider { override fun bundledAdbPath() = path }
    private class FakePath(val found: String?) : PathProbe { override fun existsOnPath(name: String) = found }

    @Test
    fun prefers_override() = runTest {
        val settings = SettingsStore(Files.createTempDirectory("adb-loc"), io = kotlinx.coroutines.Dispatchers.Unconfined)
        settings.save(Settings(adbPathOverride = "/custom/adb"))
        val loc = AdbLocator(settings, FakeBundled(null), FakePath(null))
        val bin = loc.locate()
        assertEquals("/custom/adb", bin.path)
        assertEquals(AdbSource.OVERRIDE, bin.source)
    }

    @Test
    fun falls_back_to_bundled() = runTest {
        val settings = SettingsStore(Files.createTempDirectory("adb-loc"), io = kotlinx.coroutines.Dispatchers.Unconfined)
        val loc = AdbLocator(settings, FakeBundled("/bundled/adb.exe"), FakePath(null))
        assertEquals(AdbSource.BUNDLED, loc.locate().source)
    }

    @Test
    fun falls_back_to_path() = runTest {
        val settings = SettingsStore(Files.createTempDirectory("adb-loc"), io = kotlinx.coroutines.Dispatchers.Unconfined)
        val loc = AdbLocator(settings, FakeBundled(null), FakePath("/usr/bin/adb"))
        assertEquals(AdbSource.PATH, loc.locate().source)
    }

    @Test
    fun throws_when_none() = runTest {
        val settings = SettingsStore(Files.createTempDirectory("adb-loc"), io = kotlinx.coroutines.Dispatchers.Unconfined)
        val loc = AdbLocator(settings, FakeBundled(null), FakePath(null))
        assertFailsWith<AdbNotFoundException> { loc.locate() }
    }
}
