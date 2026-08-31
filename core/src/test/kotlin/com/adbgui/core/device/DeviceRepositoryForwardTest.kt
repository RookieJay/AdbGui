package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardSpec
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceRepositoryForwardTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test
    fun listForwards_filters_to_selected_serial() = runTest {
        // R4: --list is host-wide; repo filters to the serial the UI asked about.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"),
            AdbProcessResult(0, "s1 tcp:9222 localabstract:foo\ns2 tcp:8080 localabstract:bar\n", ""))
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<com.adbgui.core.domain.DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("fwd"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 0L })
        val mine = repo.listForwards("s1")
        assertEquals(1, mine.size)
        assertEquals("s1", mine[0].serial)
        assertEquals(ForwardSpec(ForwardEndpointType.LOCALABSTRACT, "foo"), mine[0].remote)
        repo.stop()
    }
}
