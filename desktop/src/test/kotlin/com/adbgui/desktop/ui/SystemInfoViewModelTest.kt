package com.adbgui.desktop.ui

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.IDeviceTracker
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.log.NoopLogger
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemInfoViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun makeVm(
        runner: FakeAdbProcessRunner,
        selected: MutableStateFlow<String?>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<DeviceRepository, SystemInfoViewModel> {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("si"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return repo to SystemInfoViewModel(repo, selected, scope)
    }

    private fun cmdByTitle(key: String): InfoCommand =
        systemInfoCommands.first { it.titleKey == key }

    @Test
    fun runCommand_success_sets_result_clears_error() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell", "getprop"), AdbProcessResult(0, "ro.build.fingerprint=foo\n", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("abc"), this)
        vm.runCommand(cmdByTitle("si_cmd_getprop")); advanceUntilIdle()
        assertEquals("ro.build.fingerprint=foo\n", vm.result.value)  // untrimmed, matches runShellCmd contract
        assertNull(vm.error.value)
        assertEquals("si_cmd_getprop", vm.currentCommand.value?.titleKey)
        repo.stop()
    }

    @Test
    fun runCommand_failure_sets_error_with_stderr() = runTest {
        val runner = FakeAdbProcessRunner()
        // no script for `getprop` -> default AdbProcessResult(1, "", "no script matched")
        val (repo, vm) = makeVm(runner, MutableStateFlow("abc"), this)
        vm.runCommand(cmdByTitle("si_cmd_getprop")); advanceUntilIdle()
        val err = vm.error.value
        assertTrue(err != null && err.contains("no script matched"), "expected stderr in error, got: $err")
        assertNull(vm.result.value, "no result on failure")
        repo.stop()
    }

    @Test
    fun runCommand_needsPackage_without_package_sets_error_no_call() = runTest {
        val runner = FakeAdbProcessRunner()
        // If the VM wrongly called the repo, the default would surface; we assert no result + the
        // need-package error message instead.
        runner.whenArgsContains(listOf("shell"), AdbProcessResult(0, "SHOULD-NOT-HAPPEN", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("abc"), this)
        vm.runCommand(cmdByTitle("si_cmd_pm_path")); advanceUntilIdle()  // needsPackage=true
        val err = vm.error.value
        assertTrue(err != null && err.contains(Strings.t("si_need_package")), "expected need-package error, got: $err")
        assertNull(vm.result.value)
        repo.stop()
    }

    @Test
    fun runCommand_needsPackage_substitutes_package() = runTest {
        val runner = FakeAdbProcessRunner()
        // The substituted cmd is a single arg containing "com.foo"; whenArgsContains matches substrings.
        runner.whenArgsContains(listOf("com.foo"), AdbProcessResult(0, "package:/data/app/com.foo\n", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("abc"), this)
        vm.selectPackage("com.foo")
        vm.runCommand(cmdByTitle("si_cmd_pm_path")); advanceUntilIdle()
        assertEquals("package:/data/app/com.foo\n", vm.result.value)
        repo.stop()
    }

    @Test
    fun runCommand_invalid_package_sets_error() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("shell"), AdbProcessResult(0, "SHOULD-NOT-HAPPEN", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("abc"), this)
        vm.selectPackage("evil; rm -rf /")  // shell metachar -> rejected by the regex guard
        vm.runCommand(cmdByTitle("si_cmd_pm_path")); advanceUntilIdle()
        assertTrue(vm.error.value?.contains(Strings.t("si_invalid_package").substringBefore("%s")) == true,
            "expected invalid-package error, got: ${vm.error.value}")
        assertNull(vm.result.value)
        repo.stop()
    }

    @Test
    fun loadPackages_sets_packages() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\npackage:com.bar\n", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow("abc"), this)
        vm.loadPackages(); advanceUntilIdle()
        assertEquals(2, vm.packages.value.size)
        assertNull(vm.packagesError.value)
        repo.stop()
    }

    @Test
    fun loadPackages_failure_sets_packages_error() = runTest {
        val runner = FakeAdbProcessRunner()
        // no pm-list script -> default exit 1 -> listPackages throws AdbCommandException
        val (repo, vm) = makeVm(runner, MutableStateFlow("abc"), this)
        vm.loadPackages(); advanceUntilIdle()
        assertTrue(vm.packagesError.value != null, "expected packages-load error")
        assertEquals(0, vm.packages.value.size)
        repo.stop()
    }
}
