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
import com.adbgui.core.domain.Extra
import com.adbgui.core.domain.ExtraType
import com.adbgui.core.domain.InstallFlags
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConsoleViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()
            .lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n")

    private fun vm(runner: FakeAdbProcessRunner, selected: MutableStateFlow<String?>, scope: kotlinx.coroutines.CoroutineScope): Pair<DeviceRepository, AppConsoleViewModel> {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("ac"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return repo to AppConsoleViewModel(repo, selected, scope)
    }

    @Test fun install_success_sets_message() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install"), AdbProcessResult(0, "Success\n", ""))
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.install(listOf("C:/x/test.apk"), InstallFlags(reinstall = true, allowTest = false, downgrade = false, grantPerms = false)); advanceUntilIdle()
        val msg = vm.message.value
        assertTrue(msg != null && msg.contains("test.apk"), "expected success message with apk name, got: $msg")
        vm.stop(); repo.stop()
    }

    @Test fun install_failure_sets_error_and_no_message() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install"), AdbProcessResult(0, "Failure [INSTALL_FAILED_OLDER_SDK]\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.install(listOf("C:/x/test.apk"), InstallFlags(reinstall = true, allowTest = false, downgrade = false, grantPerms = false)); advanceUntilIdle()
        assertTrue(vm.error.value != null, "expected error on install failure")
        assertTrue(vm.message.value == null, "no success message on failure")
        vm.stop(); repo.stop()
    }

    @Test fun install_multiple_calls_repo_with_paths_and_flags() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("install-multiple"), AdbProcessResult(0, "Success\n", ""))
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\n", ""))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        val flags = InstallFlags(reinstall = true, allowTest = false, downgrade = true, grantPerms = false)
        vm.install(listOf("/a.apk", "/b.apk"), flags); advanceUntilIdle()
        // Verify the runner received one install-multiple call with both paths, in order.
        val calls = runner.runs.filter { it.any { a -> a.contains("install-multiple") } }
        assertEquals(1, calls.size, "expected exactly one install-multiple call, got ${calls.size}: ${runner.runs}")
        val args = calls.first()
        assertTrue(args.contains("/a.apk") && args.contains("/b.apk"), "both apks in one call: $args")
        // Downgrade flag (-d) should be present; allowTest (-t) absent.
        assertTrue(args.contains("-d"), "downgrade flag -d expected: $args")
        assertTrue(!args.contains("-t"), "allowTest flag -t should be absent: $args")
        vm.stop(); repo.stop()
    }

    @Test fun load_lists_packages() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\npackage:com.bar\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.load(); advanceUntilIdle()
        assertEquals(2, vm.packages.value.size)
        vm.stop(); repo.stop()
    }

    @Test fun no_load_before_page_entered() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\n", ""))
        val (repo, vm) = vm(runner, MutableStateFlow<String?>("abc"), this)
        advanceUntilIdle()
        assertTrue(vm.packages.value.isEmpty(), "must not list packages before the page is entered")
        assertTrue(runner.runs.none { it.contains("list") }, "no adb call before page entry")
        vm.stop(); repo.stop()
    }

    @Test fun page_entered_loads_packages() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\n", ""))
        val (repo, vm) = vm(runner, MutableStateFlow<String?>("abc"), this)
        vm.onPageEntered(); advanceUntilIdle()
        assertEquals(1, vm.packages.value.size)
        vm.stop(); repo.stop()
    }

    @Test fun forceStop_sends_command() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("force-stop"), AdbProcessResult(0, "", ""))
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.foo\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.forceStop("com.foo"); advanceUntilIdle()
        vm.stop(); repo.stop()
    }

    @Test fun startApp_sends_monkey() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("monkey"), AdbProcessResult(0, "", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.startApp("com.foo"); advanceUntilIdle()
        vm.stop(); repo.stop()
    }

    @Test fun sendBroadcast_returns_result() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("broadcast"), AdbProcessResult(0, "Broadcasting Intent { act=com.test }", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.sendBroadcast("com.test.ACTION", null, listOf(Extra(ExtraType.STRING, "k", "v"))); advanceUntilIdle()
        assertTrue(vm.broadcastResult.value?.contains("Broadcasting") == true)
        vm.stop(); repo.stop()
    }

    @Test fun queryProvider_returns_result() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("content", "query"), AdbProcessResult(0, "Row: 0 _id=1\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.queryProvider("content://settings/system", null); advanceUntilIdle()
        assertTrue(vm.providerResult.value?.contains("Row:") == true)
        vm.stop(); repo.stop()
    }

    @Test fun startActivity_calls_repo_and_sets_message() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("am", "start"), AdbProcessResult(0, "Starting: Intent\n", ""))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.startActivity(action = "VIEW", data = "x://y", component = null, extras = emptyList())
        advanceUntilIdle()
        assertEquals("Starting: Intent", vm.message.value)
        vm.stop(); repo.stop()
    }

    @Test fun startActivity_with_blank_action_and_component_does_not_call_repo() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("am", "start"), AdbProcessResult(0, "should not happen\n", ""))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.startActivity(action = "", data = null, component = "", extras = emptyList())
        advanceUntilIdle()
        assertTrue(vm.message.value == null, "no message when guard short-circuits")
        assertTrue(runner.runs.none { it.any { a -> a.contains("am") && a.contains("start") } }, "repo must not be called")
        vm.stop(); repo.stop()
    }

    @Test fun loadDetail_success_sets_detail_with_version_and_libs() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("dumpsys", "package"), AdbProcessResult(0, fixture("dumpsys_package_hisense_android9.txt"), ""))
        // listNativeLibs recurses into abi subdir: ls of /lib returns "arm" subdir; ls of /lib/arm returns .so
        runner.whenArgsContains(listOf("arm"), AdbProcessResult(0,
            "-rwxr-xr-x 1 system system 1234 2020-01-01 12:00 libcrashsdk.so\n" +
            "-rwxr-xr-x 1 system system 5678 2020-01-01 12:00 libdbapi.so\n", ""))
        runner.whenArgsContains(listOf("lib"), AdbProcessResult(0,
            "drwxr-xr-x 2 system system 4096 2020-01-01 12:00 arm\n", ""))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.loadDetail("com.dangbeimarket"); advanceUntilIdle()
        val d = vm.detail.value
        assertNotNull(d)
        assertEquals("6.0.7", d!!.versionName)
        assertEquals(613L, d.versionCode)
        assertTrue(d.nativeLibs.contains("libcrashsdk.so"), "expected libcrashsdk.so, got: ${d.nativeLibs}")
        assertNull(vm.detailError.value)
        vm.stop(); repo.stop()
    }

    @Test fun loadDetail_failure_sets_detailError() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("dumpsys", "package"), AdbProcessResult(1, "", "device offline"))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.loadDetail("com.x"); advanceUntilIdle()
        assertNull(vm.detail.value)
        assertNotNull(vm.detailError.value)
        vm.stop(); repo.stop()
    }

    @Test fun loadPermissions_sets_permissions_and_caches_dumpsys() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("dumpsys", "package"), AdbProcessResult(0, fixture("dumpsys_package_hisense_android9.txt"), ""))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.loadPermissions("com.dangbeimarket"); advanceUntilIdle()
        val perms = vm.permissions.value
        assertTrue(perms.isNotEmpty())
        // INTERNET install perm, granted, not runtime
        val internet = perms.firstOrNull { it.name == "android.permission.INTERNET" }
        assertNotNull(internet); assertTrue(internet!!.granted); assertTrue(!internet.runtime)
        vm.loadDetail("com.dangbeimarket"); advanceUntilIdle()  // should reuse cached dumpsys — no 2nd call
        val dumpsysCalls = runner.runs.count { it.any { a -> a.contains("dumpsys") } }
        assertEquals(1, dumpsysCalls, "expected dumpsys called once (cached), got $dumpsysCalls")
        vm.stop(); repo.stop()
    }

    @Test fun togglePermission_grant_updates_local_granted() = runTest {
        val runner = FakeAdbProcessRunner()
        // enchatroom fixture has runtime perms (ACCESS_FINE_LOCATION granted=true); use it so toggle is meaningful
        runner.whenArgsContains(listOf("dumpsys", "package"), AdbProcessResult(0, fixture("dumpsys_package_hisense_enchatroom_android9.txt"), ""))
        runner.whenArgsContains(listOf("pm", "revoke"), AdbProcessResult(0, "", ""))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.loadPermissions("com.speech.enchatroom"); advanceUntilIdle()
        val before = vm.permissions.value.first { it.name == "android.permission.ACCESS_FINE_LOCATION" }
        assertTrue(before.granted)
        vm.togglePermission("com.speech.enchatroom", "android.permission.ACCESS_FINE_LOCATION", grant = false); advanceUntilIdle()
        val after = vm.permissions.value.first { it.name == "android.permission.ACCESS_FINE_LOCATION" }
        assertFalse(after.granted, "expected granted flipped to false after revoke")
        vm.stop(); repo.stop()
    }

    @Test fun clearDetail_nulls_state() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("dumpsys", "package"), AdbProcessResult(0, fixture("dumpsys_package_hisense_android9.txt"), ""))
        runner.whenArgsContains(listOf("arm"), AdbProcessResult(0, "-rwxr-xr-x 1 system system 1 2020-01-01 12:00 libx.so\n", ""))
        runner.whenArgsContains(listOf("lib"), AdbProcessResult(0, "drwxr-xr-x 2 system system 1 2020-01-01 12:00 arm\n", ""))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.loadDetail("com.dangbeimarket"); advanceUntilIdle()
        assertNotNull(vm.detail.value)
        vm.clearDetail()
        assertNull(vm.detail.value); assertNull(vm.detailError.value)
        assertNull(vm.permissions.value.takeIf { it.isNotEmpty() })  // permissions cleared too
        vm.stop(); repo.stop()
    }

    @Test fun switching_serial_clears_dumpsys_cache() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("dumpsys", "package"), AdbProcessResult(0, fixture("dumpsys_package_hisense_android9.txt"), ""))
        runner.whenArgsContains(listOf("arm"), AdbProcessResult(0, "-rwxr-xr-x 1 system system 1 2020-01-01 12:00 libx.so\n", ""))
        runner.whenArgsContains(listOf("lib"), AdbProcessResult(0, "drwxr-xr-x 2 system system 1 2020-01-01 12:00 arm\n", ""))
        runner.whenArgsContains(listOf("pm", "list"), AdbProcessResult(0, "package:com.dangbeimarket\n", ""))
        val selected = MutableStateFlow<String?>("serial1")
        val (repo, vm) = vm(runner, selected, this)
        vm.onPageEntered()  // mount the serial-switch collector (no longer in init)
        vm.loadDetail("com.dangbeimarket"); advanceUntilIdle()
        assertNotNull(vm.detail.value)
        // switch device
        selected.value = "serial2"; advanceUntilIdle()
        assertNull(vm.detail.value, "detail must be cleared on serial switch")
        vm.loadDetail("com.dangbeimarket"); advanceUntilIdle()
        // dumpsys called again (cache was cleared) — 2 dumpsys calls total
        val dumpsysCalls = runner.runs.count { it.any { a -> a.contains("dumpsys") } }
        assertEquals(2, dumpsysCalls, "expected dumpsys re-called after serial switch, got $dumpsysCalls")
        vm.stop(); repo.stop()
    }
}
