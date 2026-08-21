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
import kotlin.test.assertTrue

class FileExplorerViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun vm(runner: FakeAdbProcessRunner, selected: MutableStateFlow<String?>, scope: kotlinx.coroutines.CoroutineScope): Pair<DeviceRepository, FileExplorerViewModel> {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("fe"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return repo to FileExplorerViewModel(repo, selected, scope)
    }

    @Test fun navigate_lists_entries() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0,
            "drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos\n-rw-rw---- 1 root root 123 2020-01-01 12:00 test.txt\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.navigate("/")
        advanceUntilIdle()
        assertEquals("/", vm.currentPath.value)
        assertEquals(2, vm.entries.value.size)
        assertEquals("Photos", vm.entries.value[0].name)
        vm.stop(); repo.stop()
    }

    @Test fun back_returns_to_previous_path() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0, "drwxrwx--- 2 root root 4096 2020-01-01 12:00 sub\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.navigate("/"); advanceUntilIdle()
        vm.navigate("/sdcard"); advanceUntilIdle()
        assertEquals("/sdcard", vm.currentPath.value)
        vm.back(); advanceUntilIdle()
        assertEquals("/", vm.currentPath.value)
        vm.stop(); repo.stop()
    }

    @Test fun refresh_relists_current() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0, "drwxrwx--- 2 root root 4096 2020-01-01 12:00 dir\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.navigate("/"); advanceUntilIdle()
        assertEquals(1, vm.entries.value.size)
        vm.refresh(); advanceUntilIdle()
        assertEquals(1, vm.entries.value.size)
        vm.stop(); repo.stop()
    }

    @Test fun sort_is_case_insensitive() = runTest {
        // File names mix case on Android (e.g. `charger` vs `DatabaseBackup`). Sort must be
        // case-insensitive (like Android Studio / file managers): c < d → charger before DatabaseBackup.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0,
            "lrwxrwxrwx 1 root root 21 2020-01-01 12:00 DatabaseBackup -> /userdata/DatabaseBackup\n" +
            "-rw-r--r-- 1 root root 100 2020-01-01 12:00 charger\n", ""))
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.navigate("/"); advanceUntilIdle()
        val names = vm.entries.value.map { it.name }
        assertEquals(listOf("charger", "DatabaseBackup"), names)
        vm.stop(); repo.stop()
    }

    @Test fun dir_symlink_classified_and_sorted_with_dirs() = runTest {
        // `checkSymlinkDirs` (test -d) classifies a symlink-to-dir as isDirectory=true; it must then
        // sort in the directory group. Exercises the real classification path (test -d script).
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0,
            "-rw-r--r-- 1 root root 100 2020-01-01 12:00 a_file\n" +
            "lrwxrwxrwx 1 root root 21 2020-01-01 12:00 sdcard -> /storage/self/primary\n" +
            "drwxr-xr-x 2 root root 4096 2020-01-01 12:00 z_dir\n", ""))
        runner.whenArgsContains(listOf("test", "-d"), AdbProcessResult(0, "1\n", ""))  // sdcard → dir
        val selected = MutableStateFlow<String?>("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.navigate("/"); advanceUntilIdle()
        val names = vm.entries.value.map { it.name }
        assertEquals(listOf("sdcard", "z_dir", "a_file"), names)
        val sdcard = vm.entries.value.first { it.name == "sdcard" }
        assertTrue(sdcard.isDirectory)  // classified as dir via test -d → 🔗📁, clickable, sorts with dirs
        vm.stop(); repo.stop()
    }
}
