# File Explorer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a device file browser (like AS Device File Explorer) — list directories via `adb shell ls -la`, navigate folders, right-click to upload (push) / save (pull) / refresh / copy path.

**Architecture:** Approach C — VM holds navigation state (currentPath, entries, backStack) + CommandRunner one-shot methods (ls/push/pull) + LsParser pure function. No controller (file browsing is request-response, not streaming). Mirrors AppManager pattern (VM holds state, commands are one-shot).

**Tech Stack:** Kotlin 2.1.20, Compose Multiplatform 1.7.3, JDK 21, kotlin.test. Gradle 8.11 wrapper.

**Spec:** `docs/superpowers/specs/2026-08-17-file-explorer-design.md`

## Global Constraints

- **JDK 21**; Gradle wrapper 8.11 (Tencent `distributionUrl` + Aliyun Maven mirrors already configured).
- **`:core` no UI deps.** `:desktop` UI only touches `DeviceRepository` (via VM) — never `CommandRunner`/adb directly.
- **Package roots:** `com.adbgui.core.*` / `com.adbgui.desktop.*`.
- **TDD on `:core`:** failing test → verify fails → minimal impl → verify passes → commit.
- **Commits:** Conventional Commits (`feat(core):` / `feat(desktop):`).
- **Starting path:** `/` (non-root devices get permission errors on some dirs — inline error, don't crash).
- **No delete/mkdir/rename** (out of scope per spec §8).
- **Existing signatures:** `CommandRunner.runCmd(serial, args)` builds `[-s, serial, ...args]`, runs, throws `AdbCommandException` on nonzero. Tests use `CommandRunner.AdbServerStarter{}` no-op server. `FakeAdbProcessRunner.whenArgsContains(keywords, result)`.

---

## File Structure

```
:core
├─ domain/FileEntry.kt          [new] data class FileEntry
├─ adb/LsParser.kt              [new] pure parse(stdout): List<FileEntry>
├─ adb/CommandRunner.kt         [modify] + ls / push / pull
├─ device/DeviceRepository.kt   [modify] + ls / push / pull delegate
└─ test/.../LsParserTest.kt, CommandRunnerTest.kt [modify]

:desktop
├─ ui/FileExplorerViewModel.kt  [new] navigation state + actions
├─ ui/FileExplorerScreen.kt     [new] breadcrumb + list + right-click menu
├─ ui/i18n/Strings.kt          [modify] + file_explorer keys
├─ ui/AppShell.kt              [modify] + NavPage.FILE_EXPLORER
└─ main/Main.kt                 [modify] construct FileExplorerViewModel
```

---

## Task 1: FileEntry model + LsParser

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/domain/FileEntry.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/adb/LsParser.kt`
- Create: `core/src/test/resources/fixtures/ls_la_output.txt`
- Create: `core/src/test/kotlin/com/adbgui/core/adb/LsParserTest.kt`

**Interfaces:**
- Produces: `data class FileEntry(name, isDirectory, size, date, permissions, raw)`; `object LsParser { fun parse(stdout: String): List<FileEntry> }`.

- [ ] **Step 1: Write the fixture**

`core/src/test/resources/fixtures/ls_la_output.txt`:
```
total 32
drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos
-rw-rw---- 1 root root  123 2020-01-01 12:00 test.txt
drwxr-xr-x 3 root root 4096 2020-01-02 08:30 Music
-rw-r--r-- 1 root root 4567 2021-05-15 14:22 my notes.txt
```

- [ ] **Step 2: Write the failing test**

`core/src/test/kotlin/com/adbgui/core/adb/LsParserTest.kt`:
```kotlin
package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LsParserTest {
    private val out = """
        total 32
        drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos
        -rw-rw---- 1 root root  123 2020-01-01 12:00 test.txt
        drwxr-xr-x 3 root root 4096 2020-01-02 08:30 Music
        -rw-r--r-- 1 root root 4567 2021-05-15 14:22 my notes.txt
    """.trimIndent()

    @Test fun parses_dir_and_file() {
        val list = LsParser.parse(out)
        assertEquals(4, list.size)
        assertEquals("Photos", list[0].name)
        assertTrue(list[0].isDirectory)
        assertEquals(4096, list[0].size)
        assertEquals("2020-01-01 12:00", list[0].date)
        assertEquals("drwxrwx---", list[0].permissions)
    }

    @Test fun file_not_directory() {
        val list = LsParser.parse(out)
        assertEquals("test.txt", list[1].name)
        assertTrue(!list[1].isDirectory)
        assertEquals(123, list[1].size)
    }

    @Test fun preserves_spaces_in_name() {
        val list = LsParser.parse(out)
        assertEquals("my notes.txt", list[3].name)
        assertEquals(4567, list[3].size)
    }

    @Test fun skips_total_and_dot_entries() {
        val list = LsParser.parse("total 0\n")
        assertEquals(0, list.size)
        val list2 = LsParser.parse("drwxrwx--- 2 root root 4096 2020-01-01 12:00 .\ndrwxrwx--- 2 root root 4096 2020-01-01 12:00 ..\n")
        assertEquals(0, list2.size)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :core:test --tests "*.LsParserTest" -i > /tmp/fe1.log 2>&1; tail -5 /tmp/fe1.log`
Expected: FAIL (unresolved `LsParser`/`FileEntry`).

- [ ] **Step 4: Write the model**

`core/src/main/kotlin/com/adbgui/core/domain/FileEntry.kt`:
```kotlin
package com.adbgui.core.domain

data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val date: String,
    val permissions: String,
    val raw: String,
)
```

- [ ] **Step 5: Write the parser**

`core/src/main/kotlin/com/adbgui/core/adb/LsParser.kt`:
```kotlin
package com.adbgui.core.adb

import com.adbgui.core.domain.FileEntry

object LsParser {
    // drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos
    private val re = Regex("""^([drwxst-]{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})\s+(.+)$""")

    fun parse(stdout: String): List<FileEntry> {
        return stdout.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("total")) return@mapNotNull null
                val m = re.matchEntire(trimmed) ?: return@mapNotNull null
                val perms = m.groupValues[1]
                val name = m.groupValues[5].trim()
                if (name == "." || name == "..") return@mapNotNull null
                FileEntry(
                    name = name,
                    isDirectory = perms.firstOrNull() == 'd',
                    size = m.groupValues[2].toLongOrNull() ?: 0,
                    date = "${m.groupValues[3]} ${m.groupValues[4]}",
                    permissions = perms,
                    raw = line,
                )
            }
            .toList()
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :core:test --tests "*.LsParserTest"`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/domain/FileEntry.kt core/src/main/kotlin/com/adbgui/core/adb/LsParser.kt core/src/test/resources/fixtures/ls_la_output.txt core/src/test/kotlin/com/adbgui/core/adb/LsParserTest.kt
git commit -m "feat(core): add FileEntry model and LsParser"
```

---

## Task 2: CommandRunner.ls/push/pull + DeviceRepository

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt`
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt`
- Modify: `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt`

**Interfaces:**
- Produces: `suspend fun CommandRunner.ls(serial, path): String`; `push(serial, local, device)`; `pull(serial, device, local)`. `DeviceRepository.ls/push/pull` delegates.

- [ ] **Step 1: Write the failing tests** (append to `CommandRunnerTest.kt`, inside the class before the closing `}`):

```kotlin
    @Test
    fun ls_returns_stdout_for_path() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0, "drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val out = cr.ls("abc", "/sdcard")
        assert(out.contains("Photos"))
    }

    @Test
    fun push_passes_local_and_device_path() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("push"), AdbProcessResult(0, "pushed", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.push("abc", "/local/file.txt", "/sdcard/file.txt")
        // success: no throw
    }

    @Test
    fun pull_failure_throws() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("pull"), AdbProcessResult(1, "", "device offline"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<RuntimeException> { cr.pull("abc", "/sdcard/file.txt", "/local/file.txt") }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "*.CommandRunnerTest" -i > /tmp/fe2.log 2>&1; tail -5 /tmp/fe2.log`
Expected: FAIL (unresolved `ls`/`push`/`pull`).

- [ ] **Step 3: Implement** — add to `CommandRunner.kt` (after `remount`):

```kotlin
suspend fun ls(serial: String, path: String): String {
    return runCmd(serial, listOf("shell", "ls", "-la", path)).stdout
}

suspend fun push(serial: String, localPath: String, devicePath: String) {
    runCmd(serial, listOf("push", localPath, devicePath))
}

suspend fun pull(serial: String, devicePath: String, localPath: String) {
    runCmd(serial, listOf("pull", devicePath, localPath))
}
```

Add to `DeviceRepository.kt` (after `remount`):
```kotlin
suspend fun ls(serial: String, path: String): String = commands.ls(serial, path)
suspend fun push(serial: String, localPath: String, devicePath: String) = commands.push(serial, localPath, devicePath)
suspend fun pull(serial: String, devicePath: String, localPath: String) = commands.pull(serial, devicePath, localPath)
```

- [ ] **Step 4: Run test to verify it passes + full core suite green**

Run: `./gradlew :core:test --tests "*.CommandRunnerTest" > /tmp/fe2b.log 2>&1; tail -5 /tmp/fe2b.log` then `./gradlew :core:test > /tmp/fe2c.log 2>&1; tail -4 /tmp/fe2c.log`
Expected: PASS + full suite green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt
git commit -m "feat(core): add CommandRunner ls/push/pull + Repository delegates"
```

---

## Task 3: FileExplorerViewModel

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerViewModel.kt`
- Create: `desktop/src/test/kotlin/com/adbgui/desktop/ui/FileExplorerViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceRepository.ls/push/pull` (Task 2); `LsParser.parse` (Task 1); `selectedSerial: StateFlow<String?>`; `CoroutineScope`.
- Produces: `class FileExplorerViewModel(repo, selectedSerial, scope)` with `currentPath`/`entries`/`error`/`busy` StateFlows + `navigate(path)`/`back()`/`refresh()`/`push(localPath)`/`pull(devicePath, localSavePath)` + `stop()`.

- [ ] **Step 1: Write the failing test**

`desktop/src/test/kotlin/com/adbgui/desktop/ui/FileExplorerViewModelTest.kt`:
```kotlin
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
        val history = DeviceHistoryStore(Files.createTempDirectory("fe"), clock = { 0L })
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return repo to FileExplorerViewModel(repo, selected, scope)
    }

    @Test fun navigate_lists_entries() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0,
            "drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos\n-rw-rw---- 1 root root 123 2020-01-01 12:00 test.txt\n", ""))
        val selected = MutableStateFlow("abc")
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
        val selected = MutableStateFlow("abc")
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
        val selected = MutableStateFlow("abc")
        val (repo, vm) = vm(runner, selected, this)
        vm.navigate("/"); advanceUntilIdle()
        assertEquals(1, vm.entries.value.size)
        vm.refresh(); advanceUntilIdle()
        assertEquals(1, vm.entries.value.size)
        vm.stop(); repo.stop()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :desktop:test --tests "*.FileExplorerViewModelTest" -i > /tmp/fe3.log 2>&1; tail -5 /tmp/fe3.log`
Expected: FAIL (`FileExplorerViewModel` unresolved).

- [ ] **Step 3: Implement**

`desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerViewModel.kt`:
```kotlin
package com.adbgui.desktop.ui

import com.adbgui.core.adb.LsParser
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileExplorerViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()
    private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
    val entries: StateFlow<List<FileEntry>> = _entries.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val backStack = ArrayDeque<String>()

    fun navigate(path: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            val stdout = repo.ls(serial, path)
            val parsed = LsParser.parse(stdout)
            // push current to backStack (if navigating to a different path)
            if (path != _currentPath.value) backStack.addLast(_currentPath.value)
            _currentPath.value = path
            _entries.value = parsed.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
        } finally { _busy.value = false }
    }

    fun back() = scope.launch {
        if (backStack.isEmpty()) return@launch
        val prev = backStack.removeLast()
        // navigate without pushing to backStack
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            val stdout = repo.ls(serial, prev)
            _currentPath.value = prev
            _entries.value = LsParser.parse(stdout).sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
        } finally { _busy.value = false }
    }

    fun refresh() = scope.launch {
        navigate(_currentPath.value)
    }

    fun push(localPath: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        val fileName = localPath.substringAfterLast('/')
        val devicePath = if (_currentPath.value.endsWith("/")) "${_currentPath.value}$fileName" else "${_currentPath.value}/$fileName"
        _busy.value = true; _error.value = null
        try {
            repo.push(serial, localPath, devicePath)
            refresh()
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
        } finally { _busy.value = false }
    }

    fun pull(devicePath: String, localSavePath: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            repo.pull(serial, devicePath, localSavePath)
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
        } finally { _busy.value = false }
    }

    private val refreshJob: Job = scope.launch { selectedSerial.collect { it?.let { navigate("/") } } }
    fun stop() { refreshJob.cancel() }
}
```

- [ ] **Step 4: Run test to verify it passes + full desktop suite green**

Run: `./gradlew :desktop:test --tests "*.FileExplorerViewModelTest" > /tmp/fe3b.log 2>&1; tail -5 /tmp/fe3b.log` then `./gradlew :desktop:test > /tmp/fe3c.log 2>&1; tail -4 /tmp/fe3c.log`
Expected: PASS + full suite green.

- [ ] **Step 5: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerViewModel.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/FileExplorerViewModelTest.kt
git commit -m "feat(desktop): add FileExplorerViewModel (navigate/back/refresh/push/pull)"
```

---

## Task 4: FileExplorerScreen + AppShell nav + Main wiring + i18n

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerScreen.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt` (add file_explorer keys zh+en)
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt` (NavPage.FILE_EXPLORER + fileExplorerVm param + nav button + render branch)
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt` (construct FileExplorerViewModel, pass to AppShell)

**Interfaces:**
- Consumes: `FileExplorerViewModel` (Task 3); `Strings.t(...)`; AppShell nav pattern; `openFile`/`revealFile` (v1 `FileOpen.kt`).
- Produces: `@Composable fun FileExplorerScreen(vm, selectedSerial, modifier)`; AppShell `fileExplorerVm` param + `NavPage.FILE_EXPLORER`.

- [ ] **Step 1: Add i18n keys** — in `Strings.kt`, add to BOTH `zh` and `en`:
```
// zh
"file_explorer" to "文件浏览",
"nav_file_explorer" to "文件",
"upload" to "上传",
"save_file" to "保存",
"copy_path" to "复制路径",
// en
"file_explorer" to "File Explorer",
"nav_file_explorer" to "Files",
"upload" to "Upload",
"save_file" to "Save",
"copy_path" to "Copy path",
```
(Reuse existing `refresh` key for the refresh button.)

- [ ] **Step 2: Write `FileExplorerScreen.kt`** — real Compose (layout latitude; simple + compilable):
```kotlin
package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.FileEntry
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FileExplorerScreen(
    vm: FileExplorerViewModel,
    selectedSerial: String?,
    modifier: Modifier = Modifier,
) {
    val currentPath by vm.currentPath.collectAsState()
    val entries by vm.entries.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colors.surface) {
        if (selectedSerial == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Strings.t("no_device_selected"), style = MaterialTheme.typography.body2)
            }
            return@Surface
        }
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // Breadcrumb: back + path + refresh
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.back() }) { Text("←") }
                Text(currentPath, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.refresh() }) { Text(Strings.t("refresh")) }
            }
            error?.let { e ->
                Surface(color = Color(0xFFFFCDD2), modifier = Modifier.fillMaxWidth()) {
                    Text(e, style = MaterialTheme.typography.caption, modifier = Modifier.padding(6.dp))
                }
            }
            Divider()
            // File list
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(entries) { _, entry ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { if (entry.isDirectory) vm.navigate("${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${entry.name}") }
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                                            menuOpen = true
                                        }
                                    }
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (entry.isDirectory) "📁" else "📄", modifier = Modifier.padding(end = 8.dp))
                        Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.body2)
                        Text("${entry.size}", style = MaterialTheme.typography.caption, modifier = Modifier.padding(end = 8.dp))
                        Text(entry.date, style = MaterialTheme.typography.caption)
                    }
                    // Right-click context menu
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(onClick = {
                            menuOpen = false
                            val dlg = FileDialog(Frame(), "Upload", FileDialog.LOAD)
                            dlg.isVisible = true
                            if (dlg.file != null) vm.push("${dlg.directory}${dlg.file}")
                        }) { Text(Strings.t("upload")) }
                        if (!entry.isDirectory) {
                            DropdownMenuItem(onClick = {
                                menuOpen = false
                                val dlg = FileDialog(Frame(), "Save", FileDialog.SAVE)
                                dlg.file = entry.name
                                dlg.isVisible = true
                                if (dlg.file != null) vm.pull("${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${entry.name}", "${dlg.directory}${dlg.file}")
                            }) { Text(Strings.t("save_file")) }
                        }
                        DropdownMenuItem(onClick = {
                            menuOpen = false
                            vm.refresh()
                        }) { Text(Strings.t("refresh")) }
                        DropdownMenuItem(onClick = {
                            menuOpen = false
                            val path = "${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${entry.name}"
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(path), null)
                        }) { Text(Strings.t("copy_path")) }
                    }
                }
            }
        }
    }
}
```
> Note: `@OptIn(ExperimentalComposeUiApi::class)` needed for `awaitPointerEventScope`/`PointerButton.Secondary` (same as DeviceListPane's right-click pattern). If `pointerInput` import issues arise, mirror the exact imports from `DeviceListPane.kt`.

- [ ] **Step 3: Wire AppShell** — modify `AppShell.kt`: add `fileExplorerVm: FileExplorerViewModel? = null` param; add `NavPage.FILE_EXPLORER` to the enum; add a nav `TextButton` (label `Strings.t("nav_file_explorer")`) that sets `page = NavPage.FILE_EXPLORER`; add a render branch `selected != null && page == NavPage.FILE_EXPLORER && fileExplorerVm != null -> FileExplorerScreen(vm = fileExplorerVm, selectedSerial = selected)`. Mirror the existing `NavPage.LOGCAT`/`SHELL` pattern exactly.

- [ ] **Step 4: Wire Main.kt** — add:
```kotlin
val fileExplorerVm = FileExplorerViewModel(root.repository, selectedSerial, root.scope)
```
and pass `fileExplorerVm = fileExplorerVm` into the `AppShell(...)` call. Add `import com.adbgui.desktop.ui.FileExplorerViewModel` if needed.

- [ ] **Step 5: Compile + run smoke**

Run: `./gradlew :desktop:compileKotlin > /tmp/fe4c.log 2>&1; tail -6 /tmp/fe4c.log` — BUILD SUCCESSFUL.
Then `./gradlew :desktop:test > /tmp/fe4t.log 2>&1; tail -4 /tmp/fe4t.log` — green.
Then launch smoke: `./gradlew :desktop:run` in background, `sleep 15`, confirm window opens + Files nav button appears, then kill. (With a device connected, selecting it → Files → should list `/` contents.)

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerScreen.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt
git commit -m "feat(desktop): add File Explorer screen (breadcrumb + list + right-click menu) + nav + i18n"
```

---

## Self-Review

**1. Spec coverage** — vs spec §4:
- §4.1 FileEntry → Task 1 ✅
- §4.2 LsParser → Task 1 ✅
- §4.3 CommandRunner.ls/push/pull → Task 2 ✅
- §4.4 DeviceRepository delegate → Task 2 ✅
- §4.5 FileExplorerViewModel → Task 3 ✅
- §4.6 FileExplorerScreen → Task 4 ✅
- §4.7 AppShell nav + Main + i18n → Task 4 ✅
- §6 error handling (ls/push/pull failure → inline; no device → empty) → Task 3 (VM catches) + Task 4 (screen displays) ✅
- §7 testing (LsParser + CommandRunner + VM + smoke) → Tasks 1/2/3/4 ✅
- §8 scope (no delete/mkdir/rename/preview/progress) → respected ✅

**2. Placeholder scan** — no TBD/TODO. All code blocks are complete.

**3. Type consistency** — `FileEntry(name, isDirectory, size, date, permissions, raw)` consistent Task 1→3→4. `LsParser.parse(stdout): List<FileEntry>` Task 1→3. `CommandRunner.ls(serial, path): String` / `push(serial, local, device)` / `pull(serial, device, local)` Task 2→3. `FileExplorerViewModel(repo, selectedSerial, scope)` Task 3→4. `navigate(path)`/`back()`/`refresh()`/`push(localPath)`/`pull(devicePath, localSavePath)` Task 3→4. All consistent.

No spec requirement left without a task. Plan is complete.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-18-file-explorer.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks.

**2. Inline Execution** — batch in this session with checkpoints.

Which approach?
