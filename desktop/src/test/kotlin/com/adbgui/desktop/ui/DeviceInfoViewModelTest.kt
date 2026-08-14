package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
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
import kotlin.test.assertEquals

class DeviceInfoViewModelTest {
    @Test
    fun load_parses_props() = runTest {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("di"), clock = { 0L })
        val runner = FakeAdbProcessRunner()
        val propOut = "[ro.product.model]: [Pixel 6]\n[ro.build.version.release]: [13]\n[ro.build.version.sdk]: [33]\n[ro.product.cpu.abi]: [arm64-v8a]\n"
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, propOut, ""))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 0L })
        val vm = DeviceInfoViewModel(repo, MutableStateFlow("abc"), this)
        vm.load()
        advanceUntilIdle()
        assertEquals("Pixel 6", vm.props.value?.model)
        repo.stop()
    }
}
