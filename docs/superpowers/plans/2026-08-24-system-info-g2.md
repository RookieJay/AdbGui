# System Info Page (G2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only "系统信息" (System Info) nav page that runs curated `adb shell` queries against the selected device and shows the raw stdout in a copyable/exportable text area.

**Architecture:** A new `:core` method `CommandRunner.runShellCmd(serial, cmd)` executes an arbitrary device-shell command string (pipes/redirects handled by the device's `/system/bin/sh`) and returns raw stdout, throwing `AdbCommandException` on non-zero exit. `DeviceRepository` passes it through. The desktop side is data-driven: a `List<InfoCommand>` (group + i18n title key + shell template + `needsPackage` flag) drives the UI; `SystemInfoViewModel` holds the state machine (busy/result/error + lazy package list + `{pkg}` substitution); `SystemInfoScreen` renders a left grouped command list + right `SelectableText` output area with copy + `FileDialog` export. A new `SYSTEM_INFO` nav page is wired into `AppShell` + `Main`. No new Parser (output is for humans, returned verbatim — same as `deviceDetailReport`).

**Tech Stack:** Kotlin, KMP (`:core` + `:desktop`), Compose Multiplatform Desktop, kotlinx.coroutines StateFlow, JUnit-style tests via `runTest` + `FakeAdbProcessRunner`.

**Spec:** `docs/superpowers/specs/2026-08-21-gap-analysis-and-roadmap.md` §2.2.1 (G2 detail) + §3 (nav reorg) + §7.3 (command list). Read both before starting.

## Global Constraints

- **`:core` has no UI deps.** `runShellCmd` is pure shell execution; no Compose/awt imports in `:core`.
- **UI never touches adb/CommandRunner directly.** `SystemInfoViewModel` calls only `DeviceRepository`; the screen reads only the VM's `StateFlow`s and calls only VM methods.
- **`:core` TDD.** Write the failing test first, watch it fail, implement minimal, watch it pass, commit.
- **Dispatcher injection.** No new `:core` I/O here (`runShellCmd` uses the existing `runner.run` seam, already dispatcher-safe). No `Dispatchers.IO` hardcoding.
- **No dead code.** Don't add unused params/fields. If you remove a usage, remove its declaration.
- **i18n.** All user-visible strings (group titles, command names, nav label, status messages) go through `Strings.t(...)` with both `zh` and `en` entries.
- **Errors inline, no modal.** Command failure → red error block with adb stderr folded in; package-load failure → inline; never a dialog.
- **No device selected** → buttons disabled + empty-state guidance (mirrors other screens; AppShell already only shows the page when `selected != null`).
- **Package names come from the device's own `pm list packages -3`** (constrained to `[A-Za-z0-9._]+` by Android). They are substituted into the shell template after a regex guard. (If the guard rejects, show `si_need_package`-style error — see Task 3.)
- **Conventional Commits** per task: `feat(scope):` / `fix(scope):`. One commit per task.

---

## File Structure

**`:core` (created/modified):**
- Modify `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt` — add `suspend fun runShellCmd(serial, cmd): String`.
- Modify `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt` — add one-line passthrough.
- Modify `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt` — add 2 tests.

**`:desktop` (created/modified):**
- Create `desktop/src/main/kotlin/com/adbgui/desktop/ui/InfoCommand.kt` — `data class InfoCommand` + the `systemInfoCommands: List<InfoCommand>` list.
- Create `desktop/src/test/kotlin/com/adbgui/desktop/ui/InfoCommandTest.kt` — data invariants test.
- Create `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemInfoViewModel.kt` — state machine.
- Create `desktop/src/test/kotlin/com/adbgui/desktop/ui/SystemInfoViewModelTest.kt` — state-machine tests.
- Create `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemInfoScreen.kt` — Compose UI.
- Modify `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt` — add `SYSTEM_INFO` to `NavPage`, a `systemInfoVm` param, nav button, `when` branch.
- Modify `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt` — construct + forward `systemInfoVm`.
- Modify `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt` — add `zh`+`en` entries for nav/group/command/status.

---

## Task 1: `:core` — `CommandRunner.runShellCmd` + repo passthrough (TDD)

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt` (add method near `adbVersion` ~L48)
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt` (add passthrough near `adbVersion` ~L98)
- Test: `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt`

**Interfaces:**
- Consumes: `runCmd(serial, args: List<String>): AdbProcessResult` (private, CommandRunner.kt:256) — already adds `-s <serial>`, logs, throws `AdbCommandException` on non-zero. `AdbProcessResult` has `.stdout: String`.
- Produces: `suspend fun CommandRunner.runShellCmd(serial: String, cmd: String): String` and `suspend fun DeviceRepository.runShellCmd(serial: String, cmd: String): String`. Later tasks call `repo.runShellCmd(serial, template)` from the VM.

- [ ] **Step 1: Write the failing tests**

Append to `CommandRunnerTest.kt` (inside the class, mirroring `adbVersion_returns_stdout_trimmed` at L17):

```kotlin
@Test
fun runShellCmd_passes_shell_command_and_returns_stdout() = runTest {
    // runShellCmd runs an arbitrary device-shell command string (pipes/grep handled by device sh).
    // The whole `cmd` is passed as a single arg after `shell` so the device's /system/bin/sh
    // interprets metacharacters (|, ||, 2>/dev/null) — the host does no shell parsing.
    // Returns raw stdout UNTRIMMED (spec §2.2.1 "原样返回"; adbVersion trims, this does not).
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("shell", "getprop"), AdbProcessResult(0, "ro.build.fingerprint=foo\n", ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    val out = cr.runShellCmd("ABC123", "getprop")
    assertEquals("ro.build.fingerprint=foo\n", out)  // trailing newline preserved — proves no trim
}

@Test
fun runShellCmd_nonzero_throws_adb_command_exception() = runTest {
    val runner = FakeAdbProcessRunner()
    // no script -> FakeAdbProcessRunner default = AdbProcessResult(1, "", "no script matched")
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertFailsWith<AdbCommandException> { cr.runShellCmd("ABC123", "getprop") }
}
```

Imports already present in the test file: `FakeAdbProcessRunner`, `AdbProcessResult`, `NoopLogger`, `CommandRunner`, `adb`, `runTest`, `assertEquals`, `assertTrue`. Add `import kotlin.test.assertFailsWith` and `import com.adbgui.core.domain.AdbCommandException` if not already imported (check the file's imports first).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "*CommandRunnerTest.runShellCmd*" -q`
Expected: FAIL — `runShellCmd` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `CommandRunner.kt`, add (near `adbVersion`, ~L48):

```kotlin
/** Run an arbitrary device-shell command string and return raw stdout.
 *  The whole `cmd` is passed as a single `shell` argument so the device's /system/bin/sh
 *  interprets pipes/redirects. No Parser — output is for humans, returned verbatim.
 *  Throws AdbCommandException on non-zero exit (commands where non-zero is expected,
 *  e.g. grep-no-match, should append `|| true` in their template). */
suspend fun runShellCmd(serial: String, cmd: String): String {
    return runCmd(serial, listOf("shell", cmd)).stdout
}
```

`runCmd` (L256) already builds `["-s", serial, "shell", cmd]`, logs, and throws on non-zero — reusing it is DRY and keeps logging consistent. Do **not** add a new `AdbCommandException` construction; `runCmd` owns that.

In `DeviceRepository.kt`, add (near the other one-line wrappers, ~L98–106):

```kotlin
suspend fun runShellCmd(serial: String, cmd: String): String = commands.runShellCmd(serial, cmd)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "*CommandRunnerTest*" -q`
Expected: PASS (both new tests + existing CommandRunner tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt \
        core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt \
        core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt
git commit -m "feat(core): runShellCmd — generic adb shell command for System Info (G2)"
```

---

## Task 2: `:desktop` — `InfoCommand` model + command list + i18n (TDD-ish data)

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/InfoCommand.kt`
- Create: `desktop/src/test/kotlin/com/adbgui/desktop/ui/InfoCommandTest.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt` (add to both `zh` ~L18-250 and `en` ~L252-484 maps)

**Interfaces:**
- Consumes: nothing (pure data + i18n strings).
- Produces:
  - `data class InfoCommand(val group: String, val titleKey: String, val cmd: String, val needsPackage: Boolean)`
  - `val systemInfoCommands: List<InfoCommand>` (top-level val in `InfoCommand.kt`)
  - New i18n keys: `nav_system_info`, `si_group_apps`, `si_group_display`, `si_group_system`, `si_group_network`, `si_cmd_*` (16), `si_no_command`, `si_running`, `si_empty`, `si_need_package`, `si_select_package`, `si_packages_loading`, `si_packages_failed`, `si_export_title`, `si_invalid_package`.

- [ ] **Step 1: Write the failing data-invariants test**

Create `desktop/src/test/kotlin/com/adbgui/desktop/ui/InfoCommandTest.kt`:

```kotlin
package com.adbgui.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoCommandTest {
    @Test
    fun every_needsPackage_command_has_pkg_placeholder() {
        // Commands that need a package must declare {pkg} so the VM can substitute it.
        systemInfoCommands.filter { it.needsPackage }.forEach { c ->
            assertTrue(c.cmd.contains("{pkg}"), "needsPackage command '${c.titleKey}' must contain {pkg}: ${c.cmd}")
        }
    }

    @Test
    fun every_command_is_non_blank_and_has_group_title_cmd() {
        systemInfoCommands.forEach { c ->
            assertTrue(c.cmd.isNotBlank(), "cmd blank for ${c.titleKey}")
            assertTrue(c.titleKey.isNotBlank(), "titleKey blank")
            assertTrue(c.group.isNotBlank(), "group blank for ${c.titleKey}")
        }
    }

    @Test
    fun at_least_one_command_per_group() {
        systemInfoCommands.groupBy { it.group }.forEach { (g, cmds) ->
            assertTrue(cmds.isNotEmpty(), "group $g has no commands")
        }
    }

    @Test
    fun command_count_matches_spec() {
        // Spec §7.3 lists 16 curated commands. If you add/remove, update this and the spec.
        assertEquals(16, systemInfoCommands.size, "expected 16 system info commands, got ${systemInfoCommands.size}")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :desktop:test --tests "*InfoCommandTest*" -q`
Expected: FAIL — `systemInfoCommands` unresolved.

- [ ] **Step 3: Create the model + command list**

Create `desktop/src/main/kotlin/com/adbgui/desktop/ui/InfoCommand.kt`:

```kotlin
package com.adbgui.desktop.ui

/** A read-only system-info query shown on the System Info page.
 *  @param group      i18n key for the group heading (e.g. "si_group_apps").
 *  @param titleKey   i18n key for this command's display name.
 *  @param cmd         device-shell command template. `{pkg}` is substituted with the
 *                     selected package name by the VM. Pipes/redirects are interpreted
 *                     by the device's /system/bin/sh (the host does no shell parsing).
 *  @param needsPackage true if `cmd` contains `{pkg}` and requires a selected package. */
data class InfoCommand(
    val group: String,
    val titleKey: String,
    val cmd: String,
    val needsPackage: Boolean,
)

/** Curated, data-driven command list (spec §7.3). Add/remove here only — the UI renders
 *  this list, so adding a command never touches the screen. */
val systemInfoCommands: List<InfoCommand> = listOf(
    // 应用 / Apps
    InfoCommand("si_group_apps", "si_cmd_pm_path",
        "pm path {pkg}", needsPackage = true),
    InfoCommand("si_group_apps", "si_cmd_pkg_version",
        "dumpsys package {pkg} | grep -E \"versionName|versionCode\" || true", needsPackage = true),
    InfoCommand("si_group_apps", "si_cmd_pm_features",
        "pm list features", needsPackage = false),
    InfoCommand("si_group_apps", "si_cmd_pm_libraries",
        "pm list libraries", needsPackage = false),
    // 显示 / Display
    InfoCommand("si_group_display", "si_cmd_wm_density",
        "wm density", needsPackage = false),
    InfoCommand("si_group_display", "si_cmd_current_focus",
        "dumpsys window | grep mCurrentFocus || true", needsPackage = false),
    // 系统 / System
    InfoCommand("si_group_system", "si_cmd_getprop",
        "getprop", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_diskstats",
        "dumpsys diskstats", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_df",
        "df -h", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_meminfo",
        "dumpsys meminfo", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_meminfo_pkg",
        "dumpsys meminfo {pkg}", needsPackage = true),
    InfoCommand("si_group_system", "si_cmd_top",
        "top -n 1", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_cpuinfo",
        "cat /proc/cpuinfo", needsPackage = false),
    InfoCommand("si_group_system", "si_cmd_uptime",
        "uptime", needsPackage = false),
    // 网络 / Network
    InfoCommand("si_group_network", "si_cmd_ifconfig",
        "ifconfig", needsPackage = false),
    InfoCommand("si_group_network", "si_cmd_mac",
        "cat /sys/class/net/eth0/address 2>/dev/null || cat /sys/class/net/wlan0/address 2>/dev/null || true",
        needsPackage = false),
)
```

Notes baked into the data (these are deliberate, not guesses):
- `|| true` on the two `grep` commands: `grep` exits 1 on no-match; `|| true` keeps the overall exit 0 so `runShellCmd` doesn't throw a confusing error. The VM still shows the (possibly empty) stdout.
- MAC address: `2>/dev/null` suppresses "no such file" stderr on devices without `eth0`; the `wlan0` fallback covers phones; trailing `|| true` covers devices with neither.
- `adb version` / `adb help` are **not** here — G3 already shows adb version in Settings, and `adb help` was dropped per spec §7.3.

- [ ] **Step 4: Add i18n strings**

In `Strings.kt`, add these to the `zh` map (keep keys grouped, e.g. near the existing `nav_*` and `device_tools` entries). Verbatim keys + Chinese:

```kotlin
"nav_system_info" to "系统信息",
"si_group_apps" to "应用",
"si_group_display" to "显示",
"si_group_system" to "系统",
"si_group_network" to "网络",
"si_cmd_pm_path" to "应用路径 (pm path)",
"si_cmd_pkg_version" to "应用版本",
"si_cmd_pm_features" to "硬件特性",
"si_cmd_pm_libraries" to "系统库",
"si_cmd_wm_density" to "屏幕密度",
"si_cmd_current_focus" to "当前焦点窗口",
"si_cmd_getprop" to "系统属性 (getprop)",
"si_cmd_diskstats" to "磁盘状态",
"si_cmd_df" to "磁盘可用",
"si_cmd_meminfo" to "内存信息",
"si_cmd_meminfo_pkg" to "应用内存",
"si_cmd_top" to "Top 进程",
"si_cmd_cpuinfo" to "CPU 信息",
"si_cmd_uptime" to "运行时长",
"si_cmd_ifconfig" to "网络接口",
"si_cmd_mac" to "MAC 地址",
"si_no_command" to "在左侧选择一条命令查看输出",
"si_running" to "正在执行…",
"si_empty" to "选择左侧命令查看输出",
"si_need_package" to "此命令需要先选择应用包",
"si_select_package" to "选择应用包",
"si_packages_loading" to "正在加载应用列表…",
"si_packages_failed" to "应用列表加载失败：%s",
"si_export_title" to "导出系统信息",
"si_invalid_package" to "应用包名非法：%s",
```

Add the same keys to the `en` map (English):

```kotlin
"nav_system_info" to "System info",
"si_group_apps" to "Apps",
"si_group_display" to "Display",
"si_group_system" to "System",
"si_group_network" to "Network",
"si_cmd_pm_path" to "App path (pm path)",
"si_cmd_pkg_version" to "App version",
"si_cmd_pm_features" to "Hardware features",
"si_cmd_pm_libraries" to "System libraries",
"si_cmd_wm_density" to "Screen density",
"si_cmd_current_focus" to "Current focus window",
"si_cmd_getprop" to "System properties (getprop)",
"si_cmd_diskstats" to "Disk stats",
"si_cmd_df" to "Disk free",
"si_cmd_meminfo" to "Memory info",
"si_cmd_meminfo_pkg" to "App memory",
"si_cmd_top" to "Top processes",
"si_cmd_cpuinfo" to "CPU info",
"si_cmd_uptime" to "Uptime",
"si_cmd_ifconfig" to "Network interfaces",
"si_cmd_mac" to "MAC address",
"si_no_command" to "Select a command on the left",
"si_running" to "Running…",
"si_empty" to "Select a command on the left to view output",
"si_need_package" to "Select a package first for this command",
"si_select_package" to "Select package",
"si_packages_loading" to "Loading packages…",
"si_packages_failed" to "Failed to load packages: %s",
"si_export_title" to "Export system info",
"si_invalid_package" to "Invalid package name: %s",
```

Reuse existing keys for copy/save (do not re-add): `copied`, `copy_failed` (Strings.kt zh ~L226 / en ~L460), `save`, `status_save_failed`, and the `copy`/`export` button labels already used by DeviceInfoScreen — check `Strings.kt` for the exact existing keys before adding duplicates.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :desktop:test --tests "*InfoCommandTest*" -q`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/InfoCommand.kt \
        desktop/src/test/kotlin/com/adbgui/desktop/ui/InfoCommandTest.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt
git commit -m "feat(desktop): InfoCommand model + system info command list + i18n (G2)"
```

---

## Task 3: `:desktop` — `SystemInfoViewModel` (TDD)

**Files:**
- Create: `desktop/src/test/kotlin/com/adbgui/desktop/ui/SystemInfoViewModelTest.kt`
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemInfoViewModel.kt`

**Interfaces:**
- Consumes: `DeviceRepository.runShellCmd(serial, cmd): String` (Task 1), `DeviceRepository.listPackages(serial): List<PackageInfo>` (existing), `systemInfoCommands: List<InfoCommand>` (Task 2), `AdbCommandException` (`com.adbgui.core.domain`), `Strings.t` (`com.adbgui.desktop.ui.i18n`), `PackageInfo` (`com.adbgui.core.domain`).
- Produces:
  - `class SystemInfoViewModel(repo: DeviceRepository, selectedSerial: StateFlow<String?>, scope: CoroutineScope)`
  - `val commands: List<InfoCommand>` (the static `systemInfoCommands`)
  - `val result: StateFlow<String?>`
  - `val error: StateFlow<String?>`
  - `val busy: StateFlow<Boolean>`
  - `val currentCommand: StateFlow<InfoCommand?>`
  - `val packages: StateFlow<List<PackageInfo>>`
  - `val selectedPackage: StateFlow<String?>`
  - `val packagesBusy: StateFlow<Boolean>`
  - `val packagesError: StateFlow<String?>`
  - `fun runCommand(cmd: InfoCommand): Job`
  - `fun selectPackage(pkg: String?)`
  - `fun loadPackages(): Job`
  - *(No `stop()` — the VM has no long-lived collector; see "No `stop()` — no collector" below.)*

- [ ] **Step 1: Write the failing tests**

Create `desktop/src/test/kotlin/com/adbgui/desktop/ui/SystemInfoViewModelTest.kt`. Mirror the `AppConsoleViewModelTest` repo-construction helper (L26-32 of that file) — build a real `DeviceRepository` over a `FakeAdbProcessRunner`:

```kotlin
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
```

Note: add `import com.adbgui.desktop.ui.i18n.Strings` to the test file (used in the need-package / invalid-package assertions).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :desktop:test --tests "*SystemInfoViewModelTest*" -q`
Expected: FAIL — `SystemInfoViewModel` unresolved.

- [ ] **Step 3: Implement the ViewModel**

Create `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemInfoViewModel.kt`:

```kotlin
package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.PackageInfo
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SystemInfoViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    val commands: List<InfoCommand> = systemInfoCommands

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _currentCommand = MutableStateFlow<InfoCommand?>(null)
    val currentCommand: StateFlow<InfoCommand?> = _currentCommand.asStateFlow()

    private val _packages = MutableStateFlow<List<PackageInfo>>(emptyList())
    val packages: StateFlow<List<PackageInfo>> = _packages.asStateFlow()
    private val _selectedPackage = MutableStateFlow<String?>(null)
    val selectedPackage: StateFlow<String?> = _selectedPackage.asStateFlow()
    private val _packagesBusy = MutableStateFlow(false)
    val packagesBusy: StateFlow<Boolean> = _packagesBusy.asStateFlow()
    private val _packagesError = MutableStateFlow<String?>(null)
    val packagesError: StateFlow<String?> = _packagesError.asStateFlow()
    private var packagesLoaded = false

    fun selectPackage(pkg: String?) { _selectedPackage.value = pkg }

    fun loadPackages(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _packagesBusy.value = true; _packagesError.value = null
        try {
            _packages.value = repo.listPackages(serial)
            packagesLoaded = true
        } catch (e: Exception) {
            _packagesError.value = if (e is AdbCommandException)
                Strings.t("si_packages_failed").format(e.message) else Strings.t("si_packages_failed").format(e.message ?: "")
        } finally { _packagesBusy.value = false }
    }

    fun runCommand(cmd: InfoCommand): Job = scope.launch {
        val serial = selectedSerial.value
        if (serial == null) return@launch  // AppShell hides this page when no device; defensive
        val template = cmd.cmd
        val finalCmd = if (cmd.needsPackage) {
            val pkg = _selectedPackage.value
            if (pkg.isNullOrBlank()) {
                _result.value = null
                _error.value = Strings.t("si_need_package")
                return@launch
            }
            if (!PKG_REGEX.matches(pkg)) {
                _result.value = null
                _error.value = Strings.t("si_invalid_package").format(pkg)
                return@launch
            }
            template.replace("{pkg}", pkg)
        } else template
        _busy.value = true; _error.value = null; _currentCommand.value = cmd
        try {
            _result.value = repo.runShellCmd(serial, finalCmd)
        } catch (e: Exception) {
            _result.value = null
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
        } finally { _busy.value = false }
    }

    private companion object {
        // Android package names: [A-Za-z0-9._]+. Guards against shell metachar injection from a
        // (defensively untrusted) package string. Packages normally come from the device's own
        // `pm list packages -3`, which only ever returns valid names.
        val PKG_REGEX = Regex("^[A-Za-z0-9._]+$")
    }
}
```

Decisions baked in (deliberate, not guesses):
- **`{pkg}` substitution + regex guard in the VM** (not in `:core`): `:core`'s `runShellCmd` stays a generic executor; the template/substitution is presentation logic, belongs in `:desktop`. The guard prevents shell-metachar injection if a package string ever came from elsewhere.
- **No `stop()` — no collector.** The VM does **not** auto-load packages on selection change (lazy load on dropdown click instead), so it has no long-lived `selectedSerial.collect { ... }` job and therefore no `stop()` method (CLAUDE.md §"no dead code" — `DeviceTracker.clock` / empty `scope.launch{}` were deleted for this exact reason). The tests call only `repo.stop()` (which has a real collector). If a future change adds a collector, add `stop()` then — not now.
- **No VM `export()` method.** The result text is already in `result`; the screen writes it to a `FileDialog` (mirror `DeviceInfoScreen` L104-127). No `exportBusy` needed.
- **Error format mirrors `AppConsoleViewModel`** (`"${e.message}\n--- adb stderr ---\n${e.stderr}"`) — consistent inline error + adb原文 folded, per CLAUDE.md.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :desktop:test --tests "*SystemInfoViewModelTest*" -q`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemInfoViewModel.kt \
        desktop/src/test/kotlin/com/adbgui/desktop/ui/SystemInfoViewModelTest.kt
git commit -m "feat(desktop): SystemInfoViewModel state machine for System Info (G2)"
```

---

## Task 4: `:desktop` — `SystemInfoScreen` + AppShell/Main wiring

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemInfoScreen.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt` (enum L147, params L34-49, nav buttons ~L76-96, `when` ~L109-141)
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt` (VM construction ~L41-64, AppShell call ~L81-109)

**Interfaces:**
- Consumes: `SystemInfoViewModel` (Task 3) and all its `StateFlow`s + `runCommand`/`selectPackage`/`loadPackages`; `InfoCommand` + `systemInfoCommands` (Task 2); `Strings.t`; existing patterns: `SelectableText` (`ui/SelectableText.kt`, same package — no import), `FileDialog` export (`DeviceInfoScreen.kt` L104-127), clipboard copy (`Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(...), null)` — see `FileExplorerScreen.kt` L131), existing i18n keys `copied`/`copy_failed`/`save`/`status_save_failed`.
- Produces: `@Composable fun SystemInfoScreen(vm: SystemInfoViewModel, selectedSerial: String, modifier: Modifier)`; a `SYSTEM_INFO` nav entry visible in the app.

- [ ] **Step 1: Create the screen Composable**

Create `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemInfoScreen.kt`. Layout: `Row` — left column = package `DropdownMenu` selector + grouped command `LazyColumn`; right column = header (current command title + Copy + Save buttons) + body (`SelectableText` of result / red error / empty state / busy spinner).

```kotlin
package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SystemInfoScreen(
    vm: SystemInfoViewModel,
    selectedSerial: String,
    modifier: Modifier = Modifier,
) {
    val result by vm.result.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val currentCommand by vm.currentCommand.collectAsState()
    val packages by vm.packages.collectAsState()
    val selectedPackage by vm.selectedPackage.collectAsState()
    val packagesBusy by vm.packagesBusy.collectAsState()
    val packagesError by vm.packagesError.collectAsState()

    val groups = remember { systemInfoCommands.groupBy { it.group } }
    var pkgMenuOpen by remember { mutableStateOf(false) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Row(modifier.fillMaxSize()) {
        // ---- Left: package selector + grouped command list ----
        Column(Modifier.width(300.dp).fillMaxHeight().padding(8.dp)) {
            // Package dropdown (lazy load on first open)
            Box {
                OutlinedButton(
                    onClick = {
                        if (!vm.packages.value.let { it.isNotEmpty() } && !packagesBusy) vm.loadPackages()
                        pkgMenuOpen = true
                    },
                    enabled = !packagesBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(selectedPackage ?: Strings.t("si_select_package"))
                }
                DropdownMenu(expanded = pkgMenuOpen, onDismissRequest = { pkgMenuOpen = false }) {
                    if (packagesBusy) {
                        DropdownMenuItem(onClick = {}) { Text(Strings.t("si_packages_loading")) }
                    } else {
                        packages.forEach { p ->
                            DropdownMenuItem(
                                onClick = { vm.selectPackage(p.name); pkgMenuOpen = false }
                            ) { Text(p.name) }
                        }
                    }
                }
            }
            packagesError?.let { SelectableText(it, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (groupKey, cmds) ->
                    item(key = groupKey) {
                        Text(
                            Strings.t(groupKey),
                            style = MaterialTheme.typography.subtitle1,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(cmds, key = { it.titleKey }) { c ->
                        val needsPkgAndMissing = c.needsPackage && selectedPackage == null
                        CommandRow(
                            title = Strings.t(c.titleKey),
                            enabled = !busy && !needsPkgAndMissing,
                            onClick = { vm.runCommand(c) },
                        )
                    }
                }
            }
        }

        VerticalDivider()

        // ---- Right: output ----
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // Header: title + copy + save
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    currentCommand?.let { Strings.t(it.titleKey) } ?: Strings.t("si_empty"),
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.weight(1f),
                )
                if (result != null) {
                    OutlinedButton(onClick = {
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(result!!), null)
                        }.onSuccess { savedFile = null; saveError = Strings.t("copied") }
                            .onFailure { saveError = Strings.t("copy_failed") }
                    }) { Text(Strings.t("copy")) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        val dialog = FileDialog(Frame(), Strings.t("si_export_title"), FileDialog.SAVE)
                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        dialog.file = "sysinfo_$stamp.txt"
                        dialog.isVisible = true
                        val sel = dialog.file
                        if (sel != null) {
                            val target = File(dialog.directory, sel)
                            runCatching { target.writeText(result!!) }
                                .onSuccess { savedFile = target; saveError = null }
                                .onFailure { saveError = Strings.t("status_save_failed").format(it.message) }
                        }
                    }) { Text(Strings.t("save")) }
                }
            }
            // Status line (copied / saved path / save error)
            savedFile?.let { Text(it.absolutePath, style = MaterialTheme.typography.caption) }
            saveError?.let { SelectableText(it, style = MaterialTheme.typography.caption) }

            // Body
            Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
                when {
                    busy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> SelectableText(
                        error!!,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.error,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                    result != null -> SelectableText(
                        result!!,
                        style = MaterialTheme.typography.body2,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                    else -> Text(Strings.t("si_no_command"), Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun CommandRow(title: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.fillMaxWidth())
    }
}
```

Notes:
- The screen imports `SelectableText` from the same package (`com.adbgui.desktop.ui`) — it's a top-level Composable in `ui/SelectableText.kt`, no import needed. Verify `SelectableText` accepts a `modifier` and `fontFamily`/`color` params by reading `SelectableText.kt` (L13-22) first; if its signature differs, adjust the call sites. (Per the explore report, `SelectableText(msg, style=...)` is the existing usage — wrap modifier/style/color as its signature allows.)
- `copy`/`export` button labels: check `Strings.kt` for the exact existing keys (e.g. there may already be `copy` and `save`/`export`). Use the existing keys; only add new ones if missing.
- Lazy `loadPackages()`: triggered the first time the dropdown is opened and the package list is empty. The guard `!vm.packages.value.let { it.isNotEmpty() } && !packagesBusy` re-checks at click time.

- [ ] **Step 2: Wire `SYSTEM_INFO` into `AppShell.kt`**

In `AppShell.kt`:
- **Enum (L147):** add `SYSTEM_INFO` after `SYSTEM_OPS`:
  ```kotlin
  private enum class NavPage { DEVICE_OVERVIEW, APP_CONSOLE, LOGCAT, SYSTEM_OPS, SYSTEM_INFO, FILE_EXPLORER }
  ```
- **Params (L34-49):** add a `systemInfoVm: SystemInfoViewModel?` parameter to the `AppShell` Composable signature (next to `systemOpsVm`).
- **Nav button (~L76-96):** add a `TextButton` between the `SYSTEM_OPS` and `FILE_EXPLORER` buttons, mirroring the existing nav buttons:
  ```kotlin
  TextButton(onClick = { page = NavPage.SYSTEM_INFO }, ...) { Text(Strings.t("nav_system_info")) }
  ```
  Match the existing nav button styling/`Modifier` exactly (read the surrounding buttons first).
- **`when` block (~L109-141):** add a branch before `FILE_EXPLORER`:
  ```kotlin
  selected != null && page == NavPage.SYSTEM_INFO && systemInfoVm != null -> {
      SystemInfoScreen(vm = systemInfoVm, selectedSerial = selected)
  }
  ```

- [ ] **Step 3: Construct + forward the VM in `Main.kt`**

In `Main.kt`:
- **VM construction (~L41-64):** add next to `systemOpsVm`:
  ```kotlin
  val systemInfoVm = remember { SystemInfoViewModel(root.repository, selectedSerial, root.scope) }
  ```
- **AppShell call (~L81-109):** pass `systemInfoVm = systemInfoVm`.

- [ ] **Step 4: Build + run the app and verify on a real device**

Run: `./gradlew :desktop:run`
Then with a device connected:
1. Navigate to "系统信息" — the page renders, left list shows 4 groups / 16 commands, right shows empty-state text.
2. Click "系统属性 (getprop)" — busy spinner → raw `getprop` output fills the right pane; Copy and Save buttons appear; Copy works (paste elsewhere); Save opens a Windows file dialog writing `sysinfo_<ts>.txt`.
3. Click "应用路径 (pm path)" **without** selecting a package — red `si_need_package` error.
4. Open the package dropdown (loads `pm list packages -3`), pick a package, re-run "应用路径" — substituted output appears.
5. Pull the device's adb server / pick a failing command path — red error block with `--- adb stderr ---` folded in.

If a pre-existing JVM is running from a prior session, kill it before rebuild (the `run.bat clean` / kill-app-JVM note in memory) so the new code actually runs.

- [ ] **Step 5: Run the full test suite to confirm nothing regressed**

Run: `./gradlew :core:test :desktop:test -q`
Expected: PASS (all existing + new G2 tests).

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemInfoScreen.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt
git commit -m "feat(desktop): System Info page + nav wiring (G2)"
```

---

## Self-Review (run after writing, before handing off)

**Spec coverage** (§2.2.1 / §3 / §7.3):
- `:core` `runShellCmd` → Task 1 ✅
- No new Parser → Task 1 returns raw stdout ✅
- `SystemInfoViewModel` (current command + result + busy) → Task 3 ✅
- `SystemInfoScreen` (left list + right text area) → Task 4 ✅
- AppShell nav + `SYSTEM_INFO`, nav grows → Task 4 ✅
- i18n command names + group titles → Task 2 ✅
- Package dropdown for `{pkg}` commands, disabled until selected → Task 3 (guard) + Task 4 (row disabled) ✅
- §7.3 command list (16 commands) → Task 2 ✅
- Copy + export (复用 DeviceInfo 导出模式) → Task 4 (FileDialog + clipboard) ✅
- `SelectableText` for output → Task 4 ✅
- Selected serial shown at top → Task 4 (page only renders when `selected != null`; serial available as `selectedSerial` param if a header is desired) — if you want an explicit serial label, add a `Text("adb -s $selectedSerial")` header in the right column. ✅ (optional)

**Placeholder scan:** none. (The one scaffolding line flagged in Task 3 Step 1 is explicitly called out for deletion before running.)

**Type consistency:** `runShellCmd(serial: String, cmd: String): String` — same signature in CommandRunner (Task 1), DeviceRepository (Task 1), and the VM call site (Task 3). `InfoCommand(group, titleKey, cmd, needsPackage)` — same fields in Task 2 definition, Task 3 usage, Task 4 usage. `SystemInfoViewModel(repo, selectedSerial, scope)` — same constructor in Task 3 definition and Task 4 `Main.kt` construction.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-24-system-info-g2.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
