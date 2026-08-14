package com.adbgui.core.device

import app.cash.turbine.test
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.adb.AdbStream
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.adb.AdbProcessRunner
import com.adbgui.core.adb.AdbServerController
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceTrackerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private class FakeStream(input: List<String>) : AdbStream {
        override val lines = flow { input.forEach { emit(it) } }
        override fun kill() {}
        override val isAlive = false
    }

    @Test
    fun emits_snapshots_from_track_events() = runTest {
        val runner = object : AdbProcessRunner by FakeAdbProcessRunner() {
            override fun startStream(adb: AdbBinary, args: List<String>, scope: CoroutineScope) =
                FakeStream(listOf("List of devices attached", "abc device", "xyz offline"))
        }
        val server = AdbServerController({ adb }, FakeAdbProcessRunner(), NoopLogger)
        val tracker = DeviceTracker({ adb }, server, runner, NoopLogger, this, clock = { 0L })
        tracker.devices.test {
            tracker.start()
            delay(100)
            awaitItem() // initial empty list
            awaitItem() // [abc]
            val snap = awaitItem() // [abc, xyz]
            assertEquals(2, snap.size)
            assertEquals(DeviceStatus.ONLINE, snap.first { it.serial == "abc" }.status)
            cancelAndIgnoreRemainingEvents()
        }
        tracker.stop()
    }
}
