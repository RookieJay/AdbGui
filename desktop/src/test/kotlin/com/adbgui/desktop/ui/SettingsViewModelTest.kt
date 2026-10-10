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

    @Test
    fun setTheme_persists_and_updates_state() = runTest {
        val dir = Files.createTempDirectory("theme")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setTheme("dark")
        advanceUntilIdle()
        assertEquals("dark", vm.settings.value.theme)
        assertEquals("dark", store.load().theme)
    }

    @Test
    fun setLogcatAutoRecord_persists() = runTest {
        val dir = Files.createTempDirectory("adbgui-set")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setLogcatAutoRecord(false)
        advanceUntilIdle()
        assertEquals(false, store.state.value.logcatAutoRecord)
        // 序列化默认值（true）也不丢
        vm.setLogcatAutoRecord(true)
        advanceUntilIdle()
        assertEquals(true, store.state.value.logcatAutoRecord)
    }

    @Test
    fun setCheckOnStartup_persists() = runTest {
        val dir = Files.createTempDirectory("check-on-startup")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setCheckOnStartup(false)
        advanceUntilIdle()
        assertEquals(false, vm.settings.value.update.checkOnStartup)
        assertEquals(false, store.load().update.checkOnStartup)
    }

    private val adb = com.adbgui.core.domain.AdbBinary("adb", com.adbgui.core.domain.AdbSource.PATH)

    private class FakeTracker : com.adbgui.core.device.IDeviceTracker {
        override val devices = kotlinx.coroutines.flow.MutableStateFlow<List<com.adbgui.core.domain.DeviceSnapshot>>(emptyList())
    }

    @Test
    fun setLogcatRecordingsDir_persists_and_resets() = runTest {
        val dir = Files.createTempDirectory("rec-dir")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setLogcatRecordingsDir("D:/logs")
        advanceUntilIdle()
        assertEquals("D:/logs", store.state.value.logcatRecordingsDir)
        vm.setLogcatRecordingsDir(null)
        advanceUntilIdle()
        assertEquals(null, store.state.value.logcatRecordingsDir)
    }

    @Test
    fun setLogcatRecordingMaxTotalMb_persists() = runTest {
        val dir = Files.createTempDirectory("rec-cap")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(store, this)
        vm.setLogcatRecordingMaxTotalMb(2000)
        advanceUntilIdle()
        assertEquals(2000, store.state.value.logcatRecordingMaxTotalMb)
    }

    @Test
    fun refreshRecordingsUsage_reports_total_of_auto_and_manual() = runTest {
        val recDir = Files.createTempDirectory("adbgui-rec")
        Files.createDirectories(recDir.resolve("auto")); Files.createDirectories(recDir.resolve("manual"))
        recDir.resolve("auto").resolve("a.log").toFile().writeText("x")
        recDir.resolve("manual").resolve("b.log").toFile().writeText("xy")
        val runner = com.adbgui.core.adb.FakeAdbProcessRunner()
        val cmd = com.adbgui.core.adb.CommandRunner(
            { adb }, runner, com.adbgui.core.log.NoopLogger, this, com.adbgui.core.adb.CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(recDir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        settings.update { it.copy(logcatAutoRecord = false) }
        val rec = com.adbgui.core.device.LogcatRecordingManager(
            cmd, FakeTracker(), settings, com.adbgui.core.log.NoopLogger, this, recDir,
            io = kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = SettingsViewModel(settings, this, rec)
        vm.refreshRecordingsUsage()
        advanceUntilIdle()
        assertEquals(3L, vm.recordingsUsage.value)
        rec.stop()
        advanceUntilIdle()
    }
}
