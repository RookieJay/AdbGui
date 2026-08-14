package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.AdbServerController
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceTrackerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test
    fun polls_devices_and_populates_state() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("devices"),
            AdbProcessResult(0, "List of devices attached\nabc device\nxyz offline\n", ""))
        val server = AdbServerController({ adb }, FakeAdbProcessRunner(), NoopLogger)
        val tracker = DeviceTracker({ adb }, server, runner, NoopLogger, this, clock = { 0L })
        tracker.start()
        advanceTimeBy(2100)  // let the first poll + delay cycle run
        val snap = tracker.devices.value
        assertTrue(snap.any { it.serial == "abc" && it.status == DeviceStatus.ONLINE })
        assertTrue(snap.any { it.serial == "xyz" && it.status == DeviceStatus.OFFLINE })
        tracker.stop()
    }
}
