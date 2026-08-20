package com.adbgui.desktop.ui

import com.adbgui.core.log.LogLevel
import com.adbgui.core.domain.ScrcpyLaunchProfile
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsViewModelTest {
    private suspend fun TestScope.awaitStore(store: SettingsStore, expected: LogLevel? = null, expectedPath: String? = null) {
        // bounded poll: real Dispatchers.IO needs wall time under runTest
        val deadline = 5000
        var waited = 0
        while (waited < deadline) {
            advanceUntilIdle()
            val s = store.load()
            if ((expected == null || s.logLevel == expected) && (expectedPath == null || s.adbPathOverride == expectedPath)) return
            delay(50); waited += 50
        }
        error("timed out waiting for settings persist")
    }

    @Test
    fun setAdbPath_persists_override() = runTest {
        val dir = Files.createTempDirectory("set")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setAdbPath("/x/adb")
        awaitStore(store, expectedPath = "/x/adb")
        assertEquals("/x/adb", store.load().adbPathOverride)
    }

    @Test
    fun setLogLevel_persists() = runTest {
        val dir = Files.createTempDirectory("set2")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setLogLevel(LogLevel.DEBUG)
        awaitStore(store, expected = LogLevel.DEBUG)
        assertEquals(LogLevel.DEBUG, store.load().logLevel)
    }

    @Test
    fun setScrcpyLaunch_persists_and_updates_state() = runTest {
        val dir = Files.createTempDirectory("scrcpy")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        val profile = ScrcpyLaunchProfile(maxSize = 1280, noAudio = true, recordFolder = "/rec")
        vm.setScrcpyLaunch(profile)
        advanceUntilIdle()
        assertEquals(profile, vm.settings.value.scrcpyLaunch)
        assertEquals(profile, store.load().scrcpyLaunch)
    }

    @Test
    fun setScrcpyPath_persists_override() = runTest {
        val dir = Files.createTempDirectory("scrcpy-path")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setScrcpyPath("/x/scrcpy.exe")
        advanceUntilIdle()
        assertEquals("/x/scrcpy.exe", vm.settings.value.scrcpyPathOverride)
        assertEquals("/x/scrcpy.exe", store.load().scrcpyPathOverride)
    }

    @Test
    fun setScrcpyMode_persists() = runTest {
        val dir = Files.createTempDirectory("scrcpy-mode")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setScrcpyMode("EMBEDDED")
        advanceUntilIdle()
        assertEquals("EMBEDDED", vm.settings.value.scrcpyMode)
        assertEquals("EMBEDDED", store.load().scrcpyMode)
    }
}
