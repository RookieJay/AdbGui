package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.IDeviceTracker
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.BugreportResult
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemOpsViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun vm(
        runner: FakeAdbProcessRunner,
        selected: MutableStateFlow<String?>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<DeviceRepository, SystemOpsViewModel> {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("sops"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return repo to SystemOpsViewModel(repo, selected, scope)
    }

    @Test fun bugreport_success_sets_result() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(
            listOf("bugreport"),
            AdbProcessResult(0, "Bug report is processed\r\nBug report is stored at /tmp/bugreport-x.zip\r\n", ""),
        )
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.bugreport("/tmp"); advanceUntilIdle()
        assertEquals("/tmp/bugreport-x.zip", vm.bugreportResult.value?.zipPath)
        assertTrue(vm.bugreportError.value == null, "no error on success")
        assertTrue(!vm.bugreportBusy.value, "not busy after done")
        vm.cancelBugreport(); repo.stop()
    }

    @Test fun bugreport_failure_sets_error() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("bugreport"), AdbProcessResult(1, "", "device offline"))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.bugreport("/tmp"); advanceUntilIdle()
        val err = vm.bugreportError.value
        assertTrue(err != null && err.contains("offline"), "expected stderr in error, got: $err")
        assertTrue(vm.bugreportResult.value == null, "no result on failure")
        assertTrue(!vm.bugreportBusy.value, "not busy after failure")
        vm.cancelBugreport(); repo.stop()
    }

    @Test fun bugreport_busy_does_not_block_reboot_buttons() = runTest {
        // bugreport uses _bugreportBusy, independent of _busy used by reboot/root/remount.
        // After a bugreport completes, the reboot/root/remount busy flag must never have been
        // touched (stays false). This proves the two flags are independent.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(
            listOf("bugreport"),
            AdbProcessResult(0, "Bug report is stored at /out/bugreport.zip\r\n", ""),
        )
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.bugreport("/out"); advanceUntilIdle()
        assertTrue(!vm.busy.value, "reboot/root/remount busy flag must stay false — bugreport is independent")
        assertTrue(!vm.bugreportBusy.value, "bugreport busy cleared after completion")
        assertTrue(vm.bugreportResult.value != null, "result set on success")
        vm.cancelBugreport(); repo.stop()
    }

    @Test fun bugreport_no_serial_is_noop() = runTest {
        val runner = FakeAdbProcessRunner()
        val selected = MutableStateFlow<String?>(null)
        val (repo, vm) = vm(runner, selected, this)
        vm.bugreport("/out"); advanceUntilIdle()
        assertTrue(runner.runs.none { it.any { a -> a.contains("bugreport") } }, "must not call adb with no serial")
        assertTrue(!vm.bugreportBusy.value, "not busy when no serial")
        repo.stop()
    }
}
