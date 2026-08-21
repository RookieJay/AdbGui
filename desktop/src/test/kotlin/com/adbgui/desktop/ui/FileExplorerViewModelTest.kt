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
import com.adbgui.core.domain.FileEntry
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

    @Test fun isNavigable_for_symlink_even_when_not_classified_as_dir() = runTest {
        // A symlink whose target is a directory (e.g. /sdcard -> /storage/self/primary) may NOT be
        // classified as isDirectory when `test -d` is unreliable on a device (TCL Android 6.0).
        // Navigation must still enter it — clicking follows the link via `ls -la <path>/`.
        val runner = FakeAdbProcessRunner()
        val selected = MutableStateFlow<String?>(null)
        val (repo, vm) = vm(runner, selected, this)
        val dir = FileEntry("d", isDirectory = true, isSymlink = false, 0, "", "drwxr-xr-x", "")
        val sym = FileEntry("sdcard", isDirectory = false, isSymlink = true, 0, "", "lrwxrwxrwx", "")
        val file = FileEntry("f.txt", isDirectory = false, isSymlink = false, 549, "", "-rw-r--r--", "")
        assertTrue(vm.isNavigable(dir))
        assertTrue(vm.isNavigable(sym), "symlink must be navigable even when not classified as a dir")
        assertTrue(!vm.isNavigable(file), "regular file must not be navigable")
        vm.stop(); repo.stop()
    }
}
