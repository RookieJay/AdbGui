package com.adbgui.desktop.ui

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.IDeviceTracker
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class ScreenshotViewModelTest {
    @Test
    fun capture_returns_bytes() = runTest {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("ss"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        // CommandRunner.screenshot requires a PNG signature (the banner-stripping fix); prefix it.
        runner.setBinaryResponse(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 0L })
        val vm = ScreenshotViewModel(repo, MutableStateFlow("abc"), this)
        vm.capture()
        advanceUntilIdle()
        assertTrue(vm.image.value!!.isNotEmpty())
        repo.stop()
    }
}
