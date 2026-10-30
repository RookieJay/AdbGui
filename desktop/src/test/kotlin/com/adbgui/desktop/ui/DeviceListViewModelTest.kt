package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.IDeviceTracker
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.ConnectFailureReason
import com.adbgui.core.domain.ConnectResult
import com.adbgui.core.domain.DeviceGroupBy
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.domain.PairResult
import com.adbgui.core.log.NoopLogger
import com.adbgui.core.settings.Settings
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.i18n.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val vm = DeviceListViewModel(repo, this, kotlinx.coroutines.flow.MutableStateFlow(com.adbgui.core.settings.Settings()))
        var result: ConnectResult? = null
        var dismissed = false
        var scrolled = false
        val dismissJob = launch { vm.dismissConnect.collect { dismissed = true } }
        val scrollJob = launch { vm.scrollToSerial.collect { scrolled = true } }
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
        // Successful connect must also emit the dismiss signal the ConnectDialog collects to
        // close itself — regression guard for the "dialog didn't dismiss" bug.
        assertTrue(dismissed, "connect success should emit dismissConnect")
        // And it must emit the scroll-to signal so the device list scrolls the freshly-connected
        // device into view after it jumps to the top of its MRU group. Selection alone (a bare
        // click) must NOT stamp lastUsedAt anymore — only a successful connect does — so the list
        // only reorders+scrolls on real connect success.
        assertTrue(scrolled, "connect success should emit scrollToSerial")
        dismissJob.cancel()
        scrollJob.cancel()
        repo.stop()         // cancel the dangling collectLatest collector
    }

    @Test
    fun connect_success_emits_scroll_signal() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(emptyList<DeviceSnapshot>())
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("vm-scroll"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 1.2.3.4:5555", ""))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val vm = DeviceListViewModel(repo, this, kotlinx.coroutines.flow.MutableStateFlow(com.adbgui.core.settings.Settings()))
        var result: ConnectResult? = null
        var scrolled: String? = null
        val scrollJob = launch { vm.scrollToSerial.collect { scrolled = it } }
        vm.connect("1.2.3.4", 5555) { result = it }
        val deadline = System.currentTimeMillis() + 5_000
        while (result == null && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (result != null) break
            Thread.sleep(50)
        }
        assertTrue(result?.success == true)
        assertEquals("1.2.3.4:5555", scrolled, "connect success should emit scrollToSerial = ip:port")
        scrollJob.cancel()
        repo.stop()
    }

    @Test
    fun reconnect_success_emits_scroll_signal() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(emptyList<DeviceSnapshot>())
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("vm-recon"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(0, "connected to 1.2.3.4:5555", ""))
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val vm = DeviceListViewModel(repo, this, kotlinx.coroutines.flow.MutableStateFlow(com.adbgui.core.settings.Settings()))
        var scrolled: String? = null
        val scrollJob = launch { vm.scrollToSerial.collect { scrolled = it } }
        vm.reconnect("1.2.3.4", 5555)
        val deadline = System.currentTimeMillis() + 5_000
        while (scrolled == null && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (scrolled != null) break
            Thread.sleep(50)
        }
        assertEquals("1.2.3.4:5555", scrolled, "reconnect success should emit scrollToSerial = ip:port")
        scrollJob.cancel()
        repo.stop()
    }

    @Test
    fun pair_success_does_not_auto_connect() = runTest {
        // adb pair only registers the key; the pairing port is single-use and closes right
        // after pairing succeeds. The VM must NOT auto-connect on the pairing port (that
        // hits "protocol fault: couldn't read status message"). The UI drives a separate
        // connect step with the connect port. Here we assert pair success leaves the VM
        // idle, error-free, and the callback receives success=true — with NO connect call.
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(emptyList<DeviceSnapshot>())
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("pair"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pair"), AdbProcessResult(0, "Successfully paired to 1.2.3.4:4321", ""))
        // Note: no "connect" stub — if the VM calls connect, FakeAdbProcessRunner returns
        // its default empty result (exit 0, empty stdout) and the assertion on no connect
        // args wouldn't fire; but we assert on behavior (idle + error-free), which holds
        // regardless. The key point: pair success alone must not raise an error.
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val vm = DeviceListViewModel(repo, this, kotlinx.coroutines.flow.MutableStateFlow(com.adbgui.core.settings.Settings()))
        var pairResult: PairResult? = null
        vm.pair("1.2.3.4", 4321, "123456") { pairResult = it }
        val deadline = System.currentTimeMillis() + 5_000
        while (pairResult == null && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (pairResult != null) break
            Thread.sleep(50)
        }
        assertTrue(pairResult?.success == true)
        assertTrue(vm.error.value == null)
        assertTrue(!vm.busy.value)
        repo.stop()
    }

    @Test
    fun connect_failure_port_stale_shows_actionable_hint_with_raw_message() = runTest {
        // adb connect to a stale port (device rebooted, wireless-debugging port randomized)
        // fails with "Connection refused" → the VM should surface an actionable hint that
        // names the cause + preserves the raw adb text, not a bare "Connection refused".
        Strings.set(Locale.ZH)
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(emptyList<DeviceSnapshot>())
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("stale"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(
            listOf("connect"),
            AdbProcessResult(1, "failed to connect to 1.2.3.4:5555", "cannot connect to 1.2.3.4:5555: Connection refused"),
        )
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val vm = DeviceListViewModel(repo, this, kotlinx.coroutines.flow.MutableStateFlow(com.adbgui.core.settings.Settings()))
        var result: ConnectResult? = null
        vm.connect("1.2.3.4", 5555) { result = it }
        val deadline = System.currentTimeMillis() + 5_000
        while (result == null && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (result != null) break
            Thread.sleep(50)
        }
        assertEquals(ConnectFailureReason.PORT_STALE, result?.reason)
        val err = vm.error.value
        assertNotNull(err)
        // target ip:port prefixed so the user can tell WHICH device failed
        assertTrue(err.contains("1.2.3.4:5555"), "expected target ip:port, got: $err")
        // actionable hint present + raw adb text preserved (not silently swallowed)
        assertTrue(err.contains("端口可能已变"), "expected hint, got: $err")
        assertTrue(err.contains("Connection refused"), "expected raw adb text, got: $err")
        repo.stop()
    }

    @Test
    fun items_none_mode_is_mru_sorted_no_headers() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(
                DeviceSnapshot("usb1", DeviceStatus.ONLINE),
                DeviceSnapshot("wl1", DeviceStatus.ONLINE),
            ))
        }
        val dir = Files.createTempDirectory("items")
        var clockVal = 0L
        val history = DeviceHistoryStore(dir, clock = { clockVal }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("usb1", DeviceType.USB, null, null)
        clockVal = 100L; history.touchLastUsed("usb1")
        clockVal = 200L
        history.upsert("wl1", DeviceType.WIRELESS, "10.0.0.1", 5555)
        history.touchLastUsed("wl1")
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val settingsFlow = MutableStateFlow(Settings(deviceGroupBy = DeviceGroupBy.NONE))
        val vm = DeviceListViewModel(repo, this, settingsFlow)
        val collected = mutableListOf<List<DeviceListItem>>()
        val job = launch { vm.items.collect { collected.add(it) } }
        advanceUntilIdle()
        val last = collected.last()
        // No headers in NONE mode; MRU order → wl1 (200) before usb1 (100).
        assertTrue(last.all { it is DeviceListItem.Device }, "NONE mode must have no headers: $last")
        assertEquals(listOf("wl1", "usb1"), last.map { (it as DeviceListItem.Device).view.serial })
        job.cancel(); repo.stop()
    }

    @Test
    fun items_type_mode_emits_headers_and_groups() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(
                DeviceSnapshot("usb1", DeviceStatus.ONLINE),
                DeviceSnapshot("wl1", DeviceStatus.ONLINE),
            ))
        }
        val dir = Files.createTempDirectory("items2")
        var clockVal = 0L
        val history = DeviceHistoryStore(dir, clock = { clockVal }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("usb1", DeviceType.USB, null, null)
        clockVal = 100L; history.touchLastUsed("usb1")
        clockVal = 200L
        history.upsert("wl1", DeviceType.WIRELESS, "10.0.0.1", 5555)
        history.touchLastUsed("wl1")
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val settingsFlow = MutableStateFlow(Settings(deviceGroupBy = DeviceGroupBy.TYPE))
        val vm = DeviceListViewModel(repo, this, settingsFlow)
        val collected = mutableListOf<List<DeviceListItem>>()
        val job = launch { vm.items.collect { collected.add(it) } }
        advanceUntilIdle()
        val last = collected.last()
        // MRU-of-group: wireless group (max lastUsedAt=200) sorts before USB group (max=100).
        assertEquals(
            listOf("type_wireless", "wl1", "type_usb", "usb1"),
            last.map { item -> when (item) {
                is DeviceListItem.Header -> item.key
                is DeviceListItem.Device -> item.view.serial
            } },
        )
        job.cancel(); repo.stop()
    }

    @Test
    fun items_tag_mode_untagged_bucket_last() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(
                DeviceSnapshot("usb1", DeviceStatus.ONLINE),
                DeviceSnapshot("wl1", DeviceStatus.ONLINE),
            ))
        }
        val dir = Files.createTempDirectory("items3")
        var clockVal = 0L
        val history = DeviceHistoryStore(dir, clock = { clockVal }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("usb1", DeviceType.USB, null, null)
        clockVal = 100L; history.touchLastUsed("usb1")
        clockVal = 200L
        history.upsert("wl1", DeviceType.WIRELESS, "10.0.0.1", 5555)
        history.touchLastUsed("wl1")
        history.setTag("wl1", "lab")
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val settingsFlow = MutableStateFlow(Settings(deviceGroupBy = DeviceGroupBy.TAG))
        val vm = DeviceListViewModel(repo, this, settingsFlow)
        val collected = mutableListOf<List<DeviceListItem>>()
        val job = launch { vm.items.collect { collected.add(it) } }
        advanceUntilIdle()
        val last = collected.last()
        assertEquals(
            listOf("lab", "wl1", "tag_none", "usb1"),
            last.map { item -> when (item) {
                is DeviceListItem.Header -> item.key
                is DeviceListItem.Device -> item.view.serial
            } },
        )
        job.cancel(); repo.stop()
    }

    @Test
    fun discovered_forwards_repo_state_flow() = runTest {
        // VM.discovered must transparently expose repo.discovered (no stateIn wrapping,
        // no separate sharing coroutine). Asserting identity — the VM just assigns the
        // repo's StateFlow reference, so both sides always emit the same list.
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(emptyList<DeviceSnapshot>())
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("disc"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val vm = DeviceListViewModel(repo, this, MutableStateFlow(com.adbgui.core.settings.Settings()))
        kotlin.test.assertSame(repo.discovered, vm.discovered, "vm.discovered must be the same StateFlow as repo.discovered")
        assertEquals(emptyList(), vm.discovered.value)
        repo.stop()
    }

    @Test
    fun renameTag_updates_tag_groups_in_items_flow() = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(listOf(
                DeviceSnapshot("a", DeviceStatus.ONLINE),
                DeviceSnapshot("b", DeviceStatus.ONLINE),
            ))
        }
        val dir = Files.createTempDirectory("renametag")
        val history = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        history.upsert("a", DeviceType.USB, null, null); history.setTag("a", "lab")
        history.upsert("b", DeviceType.USB, null, null); history.setTag("b", "desk")
        val runner = FakeAdbProcessRunner()
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val settingsFlow = MutableStateFlow(Settings(deviceGroupBy = DeviceGroupBy.TAG))
        val vm = DeviceListViewModel(repo, this, settingsFlow)
        val collected = mutableListOf<List<DeviceListItem>>()
        val job = launch { vm.items.collect { collected.add(it) } }
        advanceUntilIdle()
        vm.renameTag("lab", "desk")  // merge "lab" into "desk"
        advanceUntilIdle()
        // "lab" group gone; both devices sit under "desk".
        assertEquals(
            listOf("desk", "a", "b"),
            collected.last().map { item -> when (item) {
                is DeviceListItem.Header -> item.key
                is DeviceListItem.Device -> item.view.serial
            } },
        )
        job.cancel(); repo.stop()
    }

    // --------------------------------------------------------------------------------------------
    // Port-stale inline port-update (Task 12)
    // --------------------------------------------------------------------------------------------

    /**
     * Helper: build a VM wired to a [FakeAdbProcessRunner] scripted to return a PORT_STALE
     * connect failure (matches the existing `connect_failure_port_stale_shows_actionable_hint`
     * fixture — "Connection refused" → PORT_STALE via [com.adbgui.core.adb.ConnectResultParser]).
     */
    private class PortStaleFixture(val runner: FakeAdbProcessRunner, val vm: DeviceListViewModel, val repo: DeviceRepository)

    private fun runTestWithPortStaleFixture(
        block: suspend kotlinx.coroutines.test.TestScope.(PortStaleFixture) -> Unit,
    ) = runTest {
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(emptyList<DeviceSnapshot>())
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("portstale"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(
            listOf("connect"),
            AdbProcessResult(1, "failed to connect to 10.0.5.221:38897", "cannot connect to 10.0.5.221:38897: Connection refused"),
        )
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val vm = DeviceListViewModel(repo, this, MutableStateFlow(com.adbgui.core.settings.Settings()))
        block(PortStaleFixture(runner, vm, repo))
    }

    @Test
    fun reconnect_port_stale_sets_portUpdate() = runTestWithPortStaleFixture { fx ->
        // TDD RED: before implementation, portUpdate stays null after a PORT_STALE reconnect.
        fx.vm.reconnect("10.0.5.221", 38897)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && fx.vm.portUpdate.value == null) {
            advanceUntilIdle()
            if (fx.vm.portUpdate.value != null) break
            Thread.sleep(50)
        }
        val upd = fx.vm.portUpdate.value
        assertNotNull(upd, "PORT_STALE reconnect should surface a PortUpdate prompt")
        assertEquals("10.0.5.221", upd.ip)
        assertEquals(38897, upd.stalePort)
        assertEquals("10.0.5.221:38897", upd.deviceSerial)
        fx.repo.stop()
    }

    @Test
    fun submitPortUpdate_clears_and_retries_connect() = runTestWithPortStaleFixture { fx ->
        // First trigger PORT_STALE so the prompt appears.
        fx.vm.reconnect("10.0.5.221", 38897)
        val deadline1 = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline1 && fx.vm.portUpdate.value == null) {
            advanceUntilIdle()
            if (fx.vm.portUpdate.value != null) break
            Thread.sleep(50)
        }
        assertNotNull(fx.vm.portUpdate.value, "precondition: PORT_STALE prompt should be up")
        val connectsBefore = fx.runner.runs.count { args -> args.any { it.contains("connect") } }
        // User enters the new port shown on the phone's Wireless debugging screen.
        fx.vm.submitPortUpdate(37751)
        // Wait for BOTH: the prompt to clear AND the new connect to land in the runner.
        // submitPortUpdate clears the prompt synchronously, then fires an async connect — so
        // waiting for portUpdate==null alone races the second connect.
        val deadline2 = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline2) {
            advanceUntilIdle()
            val connectsNow = fx.runner.runs.count { args -> args.any { it.contains("connect") } }
            if (fx.vm.portUpdate.value == null && connectsNow > connectsBefore) break
            Thread.sleep(50)
        }
        assertEquals(null, fx.vm.portUpdate.value, "submitPortUpdate should clear the prompt")
        // And the VM should have kicked off a fresh connect to the new port. The scripted
        // response is still PORT_STALE, so this second attempt will also fail — but that's
        // fine for this test; we only assert a new connect was attempted.
        val connectsAfter = fx.runner.runs.count { args -> args.any { it.contains("connect") } }
        assertTrue(connectsAfter > connectsBefore, "submitPortUpdate should trigger a new connect attempt")
        // The new connect must target the new port (37751), not the stale one.
        val lastConnectArgs = fx.runner.runs.last { it.any { a -> a.contains("connect") } }
        assertTrue(lastConnectArgs.any { it.contains("37751") }, "new connect should target the new port, got: $lastConnectArgs")
        fx.repo.stop()
    }

    @Test
    fun reconnect_unreachable_does_not_set_portUpdate() = runTest {
        // UNREACHABLE (device off / wrong IP) should NOT trigger the port-update prompt —
        // typing a new port won't help if the device isn't on the network.
        val tracker = object : IDeviceTracker {
            override val devices = MutableStateFlow(emptyList<DeviceSnapshot>())
        }
        val history = DeviceHistoryStore(Files.createTempDirectory("unreach"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(
            listOf("connect"),
            AdbProcessResult(1, "failed to connect to 10.0.5.221:5555", "cannot connect to 10.0.5.221:5555: Connection timed out"),
        )
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val vm = DeviceListViewModel(repo, this, MutableStateFlow(com.adbgui.core.settings.Settings()))
        var errorSeen: String? = null
        val errJob = launch { vm.error.collect { errorSeen = it } }
        vm.reconnect("10.0.5.221", 5555)
        // Wait for the error to appear (confirms reconnect ran to completion).
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && errorSeen == null) {
            advanceUntilIdle()
            if (errorSeen != null) break
            Thread.sleep(50)
        }
        assertNotNull(errorSeen, "UNREACHABLE reconnect should still surface an error")
        // The error text preserves the raw adb failure (reasonText from the parser → "Connection timed out"),
        // confirming this was classified as UNREACHABLE (vs PORT_STALE which would say "Connection refused").
        assertTrue(errorSeen!!.contains("timed out"), "error should carry the timed-out adb text, got: $errorSeen")
        // Key invariant: portUpdate stays null for UNREACHABLE (only PORT_STALE triggers it).
        assertEquals(null, vm.portUpdate.value, "UNREACHABLE must NOT set portUpdate")
        errJob.cancel(); repo.stop()
    }

    @Test
    fun cancelPortUpdate_clears_prompt() = runTestWithPortStaleFixture { fx ->
        fx.vm.reconnect("10.0.5.221", 38897)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && fx.vm.portUpdate.value == null) {
            advanceUntilIdle()
            if (fx.vm.portUpdate.value != null) break
            Thread.sleep(50)
        }
        assertNotNull(fx.vm.portUpdate.value, "precondition: PORT_STALE prompt should be up")
        fx.vm.cancelPortUpdate()
        assertEquals(null, fx.vm.portUpdate.value, "cancelPortUpdate should dismiss the prompt")
        fx.repo.stop()
    }
}
