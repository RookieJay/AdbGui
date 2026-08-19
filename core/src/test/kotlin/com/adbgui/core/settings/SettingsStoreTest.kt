package com.adbgui.core.settings

import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsStoreTest {
    private fun tmpDir(): Path = Files.createTempDirectory("adbgui-test")

    @Test
    fun load_returns_defaults_when_no_file() = runTest {
        val store = SettingsStore(tmpDir(), io = kotlinx.coroutines.Dispatchers.Unconfined)
        val s = store.load()
        assertNull(s.adbPathOverride)
        assertEquals(LogLevel.INFO, s.logLevel)
        assertEquals("system", s.theme)
    }

    @Test
    fun save_then_load_roundtrip() = runTest {
        val dir = tmpDir()
        val store = SettingsStore(dir)
        store.save(Settings(adbPathOverride = "/opt/adb", logLevel = LogLevel.DEBUG, theme = "dark", windowBounds = null))
        val loaded = SettingsStore(dir).load()
        assertEquals("/opt/adb", loaded.adbPathOverride)
        assertEquals(LogLevel.DEBUG, loaded.logLevel)
    }

    @Test
    fun corrupt_file_returns_defaults() = runTest {
        val dir = tmpDir()
        Files.writeString(dir.resolve("settings.json"), "{ not json")
        val s = SettingsStore(dir).load()
        assertNull(s.adbPathOverride)
    }
}
