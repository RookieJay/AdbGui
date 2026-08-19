package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.IDeviceTracker
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.ConnectResult
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceListViewModelTest {
    @Test
    fun connect_returns_success_via_callback() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(emptyList<DeviceSnapshot>())
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("vm"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 1.2.3.4:5555", ""))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 0L })
        val vm = DeviceListViewModel(repo, this)
        var result: ConnectResult? = null
        vm.connect("1.2.3.4", 5555) { result = it }
        // DeviceHistoryStore.upsert/load switch to Dispatchers.IO (real threads); the connect
        // callback only fires after chained IO rounds complete and dispatch back to the test
        // dispatcher. A single advanceUntilIdle() races this on real-IO machines (notably
        // Windows), so drain + yield until the callback fires or we time out.
        val deadline = System.currentTimeMillis() + 5_000
        while (result == null && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (result != null) break
            Thread.sleep(50)
        }
        assertTrue(result?.success == true)
        repo.stop()         // cancel the dangling collectLatest collector
    }
}
