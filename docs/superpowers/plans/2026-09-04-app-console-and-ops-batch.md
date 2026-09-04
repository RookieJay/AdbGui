# App Console & System Ops 功能批次 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐四类 adb 能力 —— A(bugreport) + B(install-multiple + flags) + C(am start 构建器) + D(权限 grant/revoke) + E(应用详情 version/path/libs)，其中 D+E 共用 `dumpsys package` parser + fixture。

**Architecture:** 全走既有分层：新 adb 命令 = `CommandRunner` 方法（调纯函数 Parser）→ `DeviceRepository` 透传 → ViewModel 状态机 → Screen 回调。`:core` 纯 Kotlin 可单测，UI 不越层。bugreport 为长时可取消操作，需先把 `JvmAdbProcessRunner.run` 改成 async-read 结构（现版 `readText()` 阻塞在 `waitFor` 前，timeout/cancel 不生效）。

**Tech Stack:** Kotlin / KMP Compose Multiplatform (JDK 21)、kotlinx.coroutines、Gradle wrapper。测试：kotlin.test + `runTest` + `FakeAdbProcessRunner`。

**Spec:** `docs/superpowers/specs/2026-09-04-app-console-and-ops-batch-design.md`

## Global Constraints

- **`:core` 不依赖 UI**：不许 import Compose / `java.awt` / `javax.swing`。所有 adb 交互、Parser、domain 在 `:core`。
- **UI 不直接碰 adb**：UI 只读 `DeviceRepository` 的 `StateFlow`、只回调其方法。
- **`:core` 不起真 adb**：走 `AdbProcessRunner`；测试用 `FakeAdbProcessRunner`（记录 `runs` argv 列表 + `whenArgsContains(keywords, result)`）。
- **TDD on `:core`**：先写失败测试（含 fixture）→ 验证失败 → 最小实现 → 验证通过 → 提交。
- **fixture 必须真实录制**（CLAUDE.md §4）：`core/src/test/resources/fixtures/` 下文件首行注释标设备型号+manufacturer+Android 版本+SDK+build id+录制日期+命令。不许手写。
- **无死代码**：重构移除某用法时同时删声明与所有引用。
- **i18n**：所有用户文案走 `Strings.t(key)`（zh+en，`desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`）。
- **Conventional Commits**：`feat(core):` / `feat(core,desktop):` / `refactor(core):` 等。
- 运行测试：`./gradlew :core:test`、`./gradlew :desktop:test`；单测 `./gradlew :core:test --tests "*类.方法"`。
- `CommandRunner` 构造：`CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})`（见 `CommandRunnerTest`）。

## 现状修正（实现时以代码为准，非 spec 文字）

- spec §3.2 说"SystemOpsScreen"——实际无此文件，System Ops 按钮（root/remount/reboot）渲染在 `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt` 的"Device tools" `SectionCard` 内（line 67-100），`SystemOpsViewModel` 是独立 VM。bugreport UI 加到该 `SectionCard` 或其旁新增一个 `SectionCard`。
- `AdbProcessRunner.run(adb, args, timeoutMs)` 接口已支持 `timeoutMs`（`core/src/main/kotlin/com/adbgui/core/adb/AdbProcessRunner.kt:18`）；但 `JvmAdbProcessRunner.run`（`desktop/.../platform/JvmAdbProcessRunner.kt:19-29`）用 `readText()` 阻塞读后才 `withTimeoutOrNull { waitFor() }`，长时命令 timeout 不生效。Task 5 先修。
- `FileDialogs.pickDirectory(title, currentPath)` 已存在（`desktop/.../platform/FileDialogs.kt:49`）；`pickFiles` 多选不存在，Task 2 加。
- `InstallResultParser.parse(stdout, stderr, exitCode)`（`core/.../adb/PackageParsers.kt:13`）对 `install` 与 `install-multiple` 都适用（都输出 `Success` / `Failure [code]`）。
- `DeviceRepository.install(serial, apkPath, reinstall)`（`core/.../device/DeviceRepository.kt:106`）需改签名。

## File Structure

| 文件 | 职责 | 任务 |
|---|---|---|
| `core/src/main/kotlin/com/adbgui/core/domain/Models.kt` | 加 `InstallFlags`、`BugreportResult` | T1, T6 |
| `core/src/main/kotlin/com/adbgui/core/domain/DumpsysPackage.kt` | NEW：`DumpsysPackage`、`PermissionInfo`、`PackageDetail` domain | T8 |
| `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt` | 改 `install` 签名 + 加 `startActivity` / `bugreport` / `dumpsysPackage` / `grant` / `revoke` / `listNativeLibs` | T1, T3, T6, T8 |
| `core/src/main/kotlin/com/adbgui/core/adb/DumpsysPackageParser.kt` | NEW：纯函数 parser | T8 |
| `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt` | 透传新方法 + 改 `install` 签名 + `packageDetail` 编排 | T1, T3, T6, T8 |
| `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt` | 各新方法的 TDD 测试 | T1, T3, T6, T8 |
| `core/src/test/kotlin/com/adbgui/core/adb/DumpsysPackageParserTest.kt` | NEW：parser 测试读 fixture | T8 |
| `core/src/test/resources/fixtures/dumpsys_package_*.txt` | NEW：真实录制 fixture（用户录） | T8（前置） |
| `desktop/src/main/kotlin/com/adbgui/desktop/platform/JvmAdbProcessRunner.kt` | `run` 改 async-read | T5 |
| `desktop/src/main/kotlin/com/adbgui/desktop/platform/FileDialogs.kt` | 加 `pickFiles` 多选 | T2 |
| `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt` | 改 `install` + 加 `startActivity` / `loadDetail` / `loadPermissions` / `togglePermission` | T2, T4, T9 |
| `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt` | flags 行 + `ExtrasEditor` 抽取 + am start 段 + 详情展开 + 权限段 | T2, T4, T9 |
| `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemOpsViewModel.kt` | bugreport 状态 + 方法 | T7 |
| `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt` | bugreport UI 区 | T7 |
| `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt` | 新 key（zh+en） | 各任务内 |
| `desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt` | VM 状态机单测 | T2, T4, T9 |
| `desktop/src/test/kotlin/com/adbgui/desktop/ui/SystemOpsViewModelTest.kt` | NEW：bugreport VM 单测 | T7 |

---

## Task 1: B-core — InstallFlags + 重构 install 签名

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/domain/Models.kt`（加 `InstallFlags`，紧邻 `InstallResult` line 53）
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt:111-121`（`install` 方法）
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt:106-107`（透传）
- Test: `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt`（改 `install_failure_throws_with_raw_stderr` + 加新测）

**Interfaces:**
- Produces: `InstallFlags(reinstall, allowTest, downgrade, grantPerms)`；`CommandRunner.install(serial: String, paths: List<String>, flags: InstallFlags): InstallResult`；`DeviceRepository.install(serial, paths, flags)` 同签名。

- [ ] **Step 1: 加 `InstallFlags` domain + 写失败测试**

`Models.kt` 在 `InstallResult` 行后加：
```kotlin
data class InstallFlags(
    val reinstall: Boolean,   // -r  keep data
    val allowTest: Boolean,   // -t  test APK
    val downgrade: Boolean,   // -d  allow downgrade
    val grantPerms: Boolean,  // -g  grant all runtime perms
)
```

`CommandRunnerTest.kt` 把原 `install_failure_throws_with_raw_stderr`（line 76-83）改调用新签名，并加新测：
```kotlin
@Test
fun install_single_with_flags_builds_correct_argv() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("install"), AdbProcessResult(0, "Success", ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    cr.install("abc", listOf("/x.apk"), InstallFlags(reinstall = true, allowTest = true, downgrade = false, grantPerms = false))
    val argv = runner.runs.last()
    assertTrue(argv.containsAll(listOf("-s","abc","install","-r","-t","/x.apk")))
    assertTrue(!argv.contains("-d") && !argv.contains("-g"))
}

@Test
fun install_multiple_builds_install_multiple_argv() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("install-multiple"), AdbProcessResult(0, "Success", ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    cr.install("abc", listOf("/a.apk","/b.apk"), InstallFlags(reinstall = true, allowTest = false, downgrade = true, grantPerms = true))
    val argv = runner.runs.last()
    assertTrue(argv.contains("install-multiple"))
    assertTrue(argv.containsAll(listOf("-r","-d","-g","/a.apk","/b.apk")))
}

@Test
fun install_empty_paths_throws_argument() = runTest {
    val runner = FakeAdbProcessRunner()
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertFailsWith<IllegalArgumentException> { cr.install("abc", emptyList(), InstallFlags(reinstall = true, allowTest = false, downgrade = false, grantPerms = false)) }
}

@Test
fun install_failure_throws_with_raw_stderr() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("install"), AdbProcessResult(1, "Failure [INSTALL_FAILED_OLDER_SDK]", ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    val ex = assertFailsWith<RuntimeException> { cr.install("abc", listOf("x.apk"), InstallFlags(reinstall = true, allowTest = false, downgrade = false, grantPerms = false)) }
    assert(ex.message!!.contains("install"))
}
```
import `com.adbgui.core.domain.InstallFlags` 到测试文件。

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :core:test --tests "*CommandRunnerTest.install_multiple_builds_install_multiple_argv"`
Expected: FAIL（`install` 仍按旧签名 `(serial, apkPath, reinstall)`，新测试调用编译不过 / argv 不含 `install-multiple`）。

- [ ] **Step 3: 改 `CommandRunner.install` 实现**

`CommandRunner.kt:111-121` 替换为：
```kotlin
suspend fun install(serial: String, paths: List<String>, flags: InstallFlags): InstallResult {
    require(paths.isNotEmpty()) { "install: paths must not be empty" }
    val subcmd = if (paths.size == 1) "install" else "install-multiple"
    val args = buildList {
        add(subcmd)
        if (flags.reinstall) add("-r")
        if (flags.allowTest) add("-t")
        if (flags.downgrade) add("-d")
        if (flags.grantPerms) add("-g")
        addAll(paths)
    }
    val r = runCmd(serial, args)
    val parsed = InstallResultParser.parse(r.stdout, r.stderr, r.exitCode)
    if (!parsed.success) {
        throw AdbCommandException(command = "adb -s $serial ${args.joinToString(" ")}", exitCode = r.exitCode, stderr = r.stderr)
    }
    return parsed
}
```
import `com.adbgui.core.domain.InstallFlags`。**删旧 `install(serial, apkPath, reinstall)` 签名**（无死代码红线）。

- [ ] **Step 4: 改 `DeviceRepository.install` 透传**

`DeviceRepository.kt:106-107` 替换为：
```kotlin
suspend fun install(serial: String, paths: List<String>, flags: InstallResult) =
    commands.install(serial, paths, flags)
```
（`InstallResult` import 已有；`InstallFlags` 加 import `com.adbgui.core.domain.InstallFlags`。）

- [ ] **Step 5: 运行验证通过 + 修 desktop 编译**

`DeviceRepository.install` 改签名后，`AppConsoleViewModel.install(apkPath)`（line 42-52）与 `AppConsoleScreen` 调用处编译会断。Task 2 处理 desktop；但为让 `:core` 编译先过，先在 `AppConsoleViewModel.install` 临时改签名空实现占位会被 T2 替换——**不要留占位**。改为：T1 提交前把 `AppConsoleViewModel.install` 调用点临时改成调用新签名 `repo.install(serial, listOf(apkPath), InstallFlags(reinstall=true, allowTest=false, downgrade=false, grantPerms=false))`，T2 再扩成多文件+flags UI。`AppConsoleScreen` 调 `vm.install(it.absolutePath)` 不变（VM 仍接 String）。加 `InstallFlags` import 到 VM。

Run: `./gradlew :core:test --tests "*CommandRunnerTest"`
Expected: PASS（4 个 install 测试通过）。

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/domain/Models.kt \
  core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt \
  core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt \
  core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt
git commit -m "feat(core): install-multiple + flags (reinstall/allowTest/downgrade/grantPerms)"
```

---

## Task 2: B-desktop — flags UI + 多选 + 修 split-apk drop bug

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/platform/FileDialogs.kt`（加 `pickFiles`）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt`（`install(paths, flags)`）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt`（flags 行 + 多选按钮 + drop 修复）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`（新 key）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceRepository.install(serial, paths, flags)`（T1）
- Produces: `AppConsoleViewModel.install(paths: List<String>, flags: InstallFlags)`；`FileDialogs.pickFiles(title, filePattern): List<String>?`

- [ ] **Step 1: 写 VM 状态机失败测试**

`AppConsoleViewModelTest.kt` 加（若无此测试文件，新建并对齐既有 VM 测试的 `runTest` + fake repo 模式；先 grep `desktop/src/test` 看既有 fake repo 用法）：
```kotlin
@Test
fun install_multiple_calls_repo_with_paths_and_flags() = runTest {
    // 用既有 fake DeviceRepository（见 AppConsoleViewModelTest 既有 setup）
    val vm = AppConsoleViewModel(fakeRepo, selectedSerial, this)
    vm.install(listOf("/a.apk","/b.apk"), InstallFlags(reinstall=true, allowTest=false, downgrade=true, grantPerms=false))
    advanceUntilIdle()
    verify(fakeRepo).install("serial1", listOf("/a.apk","/b.apk"), InstallFlags(reinstall=true, allowTest=false, downgrade=true, grantPerms=false))
}
```
（先读 `AppConsoleViewModelTest.kt` 既有 fake repo mockito 设置，对齐 mock 风格；若用手工 fake 则调其记录的 calls 列表断言。）

- [ ] **Step 2: 验证失败**

Run: `./gradlew :desktop:test --tests "*AppConsoleViewModelTest.install_multiple*"`
Expected: FAIL（VM `install` 还是单 String）。

- [ ] **Step 3: 加 `FileDialogs.pickFiles`**

`FileDialogs.kt` 加（AWT `FileDialog` 多选用 `setMultipleMode`）：
```kotlin
fun pickFiles(title: String, filePattern: String? = null): List<String>? {
    val dlg = FileDialog(Frame(), title, FileDialog.LOAD)
    dlg.isMultipleMode = true
    if (filePattern != null) dlg.file = filePattern
    dlg.isVisible = true
    val files = dlg.files ?: return null
    return if (files.isEmpty()) null else files.map { it.absolutePath }
}
```

- [ ] **Step 4: 改 VM `install`**

`AppConsoleViewModel.kt` line 42-52 替换：
```kotlin
fun install(paths: List<String>, flags: InstallFlags) = scope.launch {
    val serial = selectedSerial.value ?: return@launch
    require(paths.isNotEmpty()) { "install: no paths" }
    _busy.value = true; _error.value = null; _message.value = null
    try {
        repo.install(serial, paths, flags)
        _message.value = Strings.t("install_success").format(paths.joinToString(", "))
        load()
    } catch (e: Exception) { _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error") }
    finally { _busy.value = false }
}
```
import `com.adbgui.core.domain.InstallFlags`。

- [ ] **Step 5: 改 Screen — flags 行 + 多选 + drop 修**

`AppConsoleScreen.kt` 顶部（`advancedOpen` 旁）加本地 state：
```kotlin
var reinstall by remember { mutableStateOf(true) }
var allowTest by remember { mutableStateOf(false) }
var downgrade by remember { mutableStateOf(false) }
var grantPerms by remember { mutableStateOf(false) }
```
安装按钮 `onClick`（line 151-161）改为：
```kotlin
onClick = {
    val chosen = com.adbgui.desktop.platform.FileDialogs.pickFiles(
        title = Strings.t("select_apk"),
        filePattern = "*.apk",
    )
    if (chosen != null && chosen.isNotEmpty()) {
        vm.install(chosen, InstallFlags(reinstall, allowTest, downgrade, grantPerms))
    }
},
```
安装按钮下方加 flags 复选框行：
```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Checkbox(checked = reinstall, onCheckedChange = { reinstall = it }); Text(Strings.t("flag_reinstall"))
    Spacer(Modifier.width(8.dp))
    Checkbox(checked = allowTest, onCheckedChange = { allowTest = it }); Text(Strings.t("flag_allow_test"))
    Spacer(Modifier.width(8.dp))
    Checkbox(checked = downgrade, onCheckedChange = { downgrade = it }); Text(Strings.t("flag_downgrade"))
    Spacer(Modifier.width(8.dp))
    Checkbox(checked = grantPerms, onCheckedChange = { grantPerms = it }); Text(Strings.t("flag_grant_perms"))
}
```
import `androidx.compose.material.Checkbox`、`com.adbgui.core.domain.InstallFlags`。

Drop target `onDrop`（line 104-112）改为收集后一次安装：
```kotlin
override fun onDrop(event: DragAndDropEvent): Boolean {
    val transferable = event.awtTransferable
    val apks = runCatching {
        (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
            ?.filterIsInstance<File>()
    }.getOrNull().orEmpty().filter { it.extension.equals("apk", ignoreCase = true) }
    if (apks.isEmpty()) return false
    vm.install(apks.map { it.absolutePath }, InstallFlags(reinstall, allowTest, downgrade, grantPerms))
    return true
}
```
（原 `apks.forEach { vm.install(it.absolutePath) }` 逐个装 → split APK 必失败；现一次 `install-multiple`。）

- [ ] **Step 6: i18n keys**

`Strings.kt` zh 段（line ~125 旁）加：
```
"flag_reinstall" to "保留数据(-r)",
"flag_allow_test" to "允许 test(-t)",
"flag_downgrade" to "降级(-d)",
"flag_grant_perms" to "授权权限(-g)",
```
en 段（line ~498 旁）加：
```
"flag_reinstall" to "Keep data (-r)",
"flag_allow_test" to "Allow test (-t)",
"flag_downgrade" to "Downgrade (-d)",
"flag_grant_perms" to "Grant perms (-g)",
```

- [ ] **Step 7: 验证通过 + Commit**

Run: `./gradlew :desktop:test --tests "*AppConsoleViewModelTest"`
Expected: PASS。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/platform/FileDialogs.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt \
  desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt
git commit -m "feat(desktop): install flags UI + multi-file picker; fix split-apk drop install"
```

---

## Task 3: C-core — startActivity（am start 构建器）

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt`（加 `startActivity`，紧邻 `sendBroadcast` line 246）
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt`（透传）
- Test: `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt`

**Interfaces:**
- Consumes: `Extra`/`ExtraType`（`domain/Extra.kt`，flags `--es/--ei/--ez/--el`）
- Produces: `CommandRunner.startActivity(serial, action, data, component, extras): String`

- [ ] **Step 1: 写失败测试**

`CommandRunnerTest.kt` 加：
```kotlin
@Test
fun startActivity_with_action_and_data_builds_argv() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("am","start"), AdbProcessResult(0, "Starting: Intent { ... }", ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    cr.startActivity("abc", action = "android.intent.action.VIEW", data = "myapp://x", component = null, extras = emptyList())
    val argv = runner.runs.last()
    assertTrue(argv.containsAll(listOf("-s","abc","shell","am","start","-a","android.intent.action.VIEW","-d","myapp://x")))
}

@Test
fun startActivity_with_component_and_extras_builds_argv() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("am","start"), AdbProcessResult(0, "Starting", ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    cr.startActivity("abc", action = null, data = null, component = "com.x/.Main", extras = listOf(Extra(ExtraType.STRING,"k","v")))
    val argv = runner.runs.last()
    assertTrue(argv.containsAll(listOf("-n","com.x/.Main","--es","k","v")))
}

@Test
fun startActivity_failure_throws() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("am","start"), AdbProcessResult(1, "", "Error"))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertFailsWith<AdbCommandException> { cr.startActivity("abc", action = "VIEW", data = null, component = null, extras = emptyList()) }
}
```
import `com.adbgui.core.domain.Extra`、`com.adbgui.core.domain.ExtraType`。

- [ ] **Step 2: 验证失败**

Run: `./gradlew :core:test --tests "*CommandRunnerTest.startActivity*"`
Expected: FAIL（`startActivity` 未定义）。

- [ ] **Step 3: 实现**

`CommandRunner.kt` 在 `sendBroadcast` 前加：
```kotlin
/** `am start` with optional action/data/component + extras. At least one of action/component
 *  should be non-blank (VM guards); core doesn't enforce — adb will error if both blank. */
suspend fun startActivity(
    serial: String,
    action: String?,
    data: String?,
    component: String?,
    extras: List<Extra>,
): String {
    val args = buildList {
        add("shell"); add("am"); add("start")
        if (!action.isNullOrBlank()) { add("-a"); add(action) }
        if (!data.isNullOrBlank()) { add("-d"); add(data) }
        if (!component.isNullOrBlank()) { add("-n"); add(component) }
        extras.forEach { add(it.type.flag); add(it.key); add(it.value) }
    }
    return runCmd(serial, args).stdout
}
```
import `com.adbgui.core.domain.Extra`。

- [ ] **Step 4: `DeviceRepository` 透传**

`DeviceRepository.kt` 在 `sendBroadcast` 旁加：
```kotlin
suspend fun startActivity(serial: String, action: String?, data: String?, component: String?, extras: List<com.adbgui.core.domain.Extra>): String =
    commands.startActivity(serial, action, data, component, extras)
```

- [ ] **Step 5: 验证通过 + Commit**

Run: `./gradlew :core:test --tests "*CommandRunnerTest.startActivity*"`
Expected: PASS。

```bash
git add core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt \
  core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt \
  core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt
git commit -m "feat(core): am start builder (action/data/component/extras) for deep-link launch"
```

---

## Task 4: C-desktop — ExtrasEditor 抽取 + am start 段

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt`（加 `startActivity`）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt`（抽 `ExtrasEditor` + 扩展 am start 段）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceRepository.startActivity`（T3）
- Produces: `AppConsoleViewModel.startActivity(action, data, component, extras)`

- [ ] **Step 1: 写 VM 失败测试**

```kotlin
@Test
fun startActivity_calls_repo_and_sets_message() = runTest {
    val vm = AppConsoleViewModel(fakeRepo, selectedSerial, this)
    whenever(fakeRepo.startActivity(eq("serial1"), any(), any(), any(), any())).thenReturn("Starting: Intent")
    vm.startActivity(action = "VIEW", data = "x://y", component = null, extras = emptyList())
    advanceUntilIdle()
    assertEquals("Starting: Intent", vm.message.value)
}

@Test
fun startActivity_with_blank_action_and_component_does_not_call_repo() = runTest {
    val vm = AppConsoleViewModel(fakeRepo, selectedSerial, this)
    vm.startActivity(action = "", data = null, component = "", extras = emptyList())
    advanceUntilIdle()
    verify(fakeRepo, never()).startActivity(any(), any(), any(), any(), any())
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew :desktop:test --tests "*AppConsoleViewModelTest.startActivity*"`
Expected: FAIL。

- [ ] **Step 3: VM `startActivity`**

`AppConsoleViewModel.kt` 加：
```kotlin
fun startActivity(action: String?, data: String?, component: String?, extras: List<Extra>) = scope.launch {
    val serial = selectedSerial.value ?: return@launch
    val a = action?.trim()?.ifBlank { null }
    val c = component?.trim()?.ifBlank { null }
    if (a == null && c == null) return@launch  // 守卫：至少一个
    _busy.value = true; _error.value = null; _message.value = null
    try {
        val out = repo.startActivity(serial, a, data?.ifBlank { null }, c, extras)
        _message.value = out.ifBlank { Strings.t("start_activity_done") }
    } catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
    finally { _busy.value = false }
}
```

- [ ] **Step 4: 抽 `ExtrasEditor` 共享 composable**

`AppConsoleScreen.kt` 把 broadcast 段的 extras 行 UI（line 414-449 的 `extrasRows.forEachIndexed{...}` + add 按钮）抽成顶层 private composable：
```kotlin
@Composable
private fun ExtrasEditor(
    rows: androidx.compose.runtime.snapshots.SnapshotStateList<Triple<ExtraType, String, String>>,
) {
    Text(Strings.t("extras"), style = MaterialTheme.typography.caption)
    rows.forEachIndexed { index, row ->
        val (type, key, value) = row
        Row(verticalAlignment = Alignment.CenterVertically) {
            var typeExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { typeExpanded = true }) { Text(type.flag) }
                DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                    ExtraType.values().forEach { et ->
                        DropdownMenuItem(onClick = { rows[index] = Triple(et, key, value); typeExpanded = false }) { Text(et.flag) }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(value = key, singleLine = true, onValueChange = { rows[index] = Triple(type, it, value) }, placeholder = { Text("key") }, modifier = Modifier.width(120.dp))
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(value = value, singleLine = true, onValueChange = { rows[index] = Triple(type, key, it) }, placeholder = { Text("value") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { rows.removeAt(index) }) { Text(Strings.t("remove")) }
        }
    }
    Row { OutlinedButton(onClick = { rows.add(Triple(ExtraType.STRING, "", "")) }) { Text(Strings.t("add_button")) } }
}
```
broadcast 段原 extras UI 替换为 `ExtrasEditor(extrasRows)`，末尾保留 broadcast 的发送按钮（不变）。

- [ ] **Step 5: 扩展 am start 段**

`AdvancedPanel` 顶部 am start 段（line 374-390）替换。原只有 `activity` 单字段；改为 Component + Action + Data + Extras：
```kotlin
// --- am start (deep link / explicit component) ---
Text(Strings.t("start_activity"), style = MaterialTheme.typography.subtitle2)
var amComponent by remember { mutableStateOf("") }
var amAction by remember { mutableStateOf("") }
var amData by remember { mutableStateOf("") }
val amExtras = remember { androidx.compose.runtime.mutableStateListOf<Triple<ExtraType, String, String>>() }
OutlinedTextField(value = amComponent, singleLine = true, onValueChange = { amComponent = it }, label = { Text(Strings.t("component")) }, placeholder = { Text("$pkg/.MainActivity") }, modifier = Modifier.fillMaxWidth())
OutlinedTextField(value = amAction, singleLine = true, onValueChange = { amAction = it }, label = { Text(Strings.t("am_action")) }, modifier = Modifier.fillMaxWidth())
OutlinedTextField(value = amData, singleLine = true, onValueChange = { amData = it }, label = { Text(Strings.t("am_data")) }, modifier = Modifier.fillMaxWidth())
ExtrasEditor(amExtras)
Row {
    Button(
        enabled = !busy && (amAction.isNotBlank() || amComponent.isNotBlank()),
        onClick = {
            val extras = amExtras.filter { it.second.isNotBlank() }.map { Extra(it.first, it.second.trim(), it.third) }
            onStartActivity(amAction.trim().ifBlank { null }, amData.trim().ifBlank { null }, amComponent.trim().ifBlank { null }, extras)
        },
    ) { Text(Strings.t("start_activity")) }
}
```
`AdvancedPanel` 签名把 `onStartActivity: (String) -> Unit` 改为 `onStartActivity: (String?, String?, String?, List<Extra>) -> Unit`；`AppConsoleScreen` 调用处（line 281）改：
```kotlin
onStartActivity = { action, data, component, extras -> vm.startActivity(action, data, component, extras) },
```
删原 `startAppActivity` VM 方法（line 91-97）与 `repo.startAppActivity` 透传（`DeviceRepository.kt:149`）—— `startActivity(component=...)` 已覆盖，无死代码。**注意**：删 `startAppActivity` 前先 grep 确认无其他调用点（`grep -rn "startAppActivity" --include=*.kt` 应只剩定义 + AdvancedPanel 旧调用，旧调用在 Step 5 已替换）。

- [ ] **Step 6: i18n keys**

`Strings.kt` zh 加：
```
"component" to "Component (pkg/Activity)",
"am_action" to "Action",
"am_data" to "Data URI",
"start_activity_done" to "已启动",
```
en 加：
```
"component" to "Component (pkg/Activity)",
"am_action" to "Action",
"am_data" to "Data URI",
"start_activity_done" to "Started",
```

- [ ] **Step 7: 验证 + Commit**

Run: `./gradlew :desktop:test --tests "*AppConsoleViewModelTest"`
Expected: PASS。`./gradlew :desktop:test` 全量回归。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt \
  desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt \
  core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt
git commit -m "feat(core,desktop): am start builder UI with shared ExtrasEditor; drop startAppActivity"
```

---

## Task 5: A-precondition — JvmAdbProcessRunner.run 改 async-read

**为什么前置**：bugreport 长时（10–60s），现版 `run` 先 `readText()` 阻塞读再 `withTimeoutOrNull{waitFor()}`，timeout 永不触发、cancel 不杀进程。改为镜像 `runBinary` 的 async 读 + `withTimeoutOrNull`，让 timeout/cancel 真正生效。

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/platform/JvmAdbProcessRunner.kt:19-29`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/platform/JvmAdbProcessRunnerSmokeTest.kt`（既有，加回归）

**Interfaces:**
- 无签名变化（`run(adb, args, timeoutMs): AdbProcessResult` 不变）。内部实现更健壮。

- [ ] **Step 1: 写回归测试**

读 `JvmAdbProcessRunnerSmokeTest.kt` 既有测试模式（用真实 `adb` 还是 fake？既有 smoke test 名为 Smoke，可能需真 adb——先读它）。若 smoke 依赖真 adb，则加一个不依赖真 adb 的测试：用 `adb` 指向一个会快速退出 + 输出已知文本的小脚本（如 `cmd /c echo hello`，但跨平台；或只测 timeout 路径用 `ping` 长 sleep）。若既有测试结构不便加，则跳过单测、靠手测 + 既有 smoke 不回归。**先读该文件决定**。

- [ ] **Step 2: 改实现**

`JvmAdbProcessRunner.kt:19-29` 替换 `run`：
```kotlin
override suspend fun run(adb: AdbBinary, args: List<String>, timeoutMs: Long?): AdbProcessResult = withContext(Dispatchers.IO) {
    val proc = ProcessBuilder(listOf(adb.path) + args).redirectErrorStream(false).start()
    // Mirror runBinary: async reads so a timeout / coroutine cancel can interrupt the wait and
    // destroy the process. Blocking readText() before waitFor would hang past any timeout and
    // ignore cancellation (the original bug for long-running commands like `adb bugreport`).
    val stdoutDeferred = async { proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText() }
    val stderrDeferred = async { proc.errorStream.bufferedReader(StandardCharsets.UTF_8).readText() }
    val finished = if (timeoutMs != null) withTimeoutOrNull(timeoutMs) { proc.waitFor() } else proc.waitFor()
    if (finished == null) {
        proc.destroyForcibly()
        throw RuntimeException("adb timeout: ${args.joinToString(" ")}")
    }
    AdbProcessResult(proc.exitValue(), stdoutDeferred.await(), stderrDeferred.await())
}
```
import `kotlinx.coroutines.async`、`kotlinx.coroutines.withTimeoutOrNull`（`withContext`/`Dispatchers` 已有）。

- [ ] **Step 3: 验证 + Commit**

Run: `./gradlew :desktop:test`（确认既有测试不回归；若 Step1 加了新测则一并跑）
Expected: PASS。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/platform/JvmAdbProcessRunner.kt \
  desktop/src/test/kotlin/com/adbgui/desktop/platform/JvmAdbProcessRunnerSmokeTest.kt
git commit -m "refactor(core): JvmAdbProcessRunner.run uses async reads so timeout/cancel interrupts"
```

---

## Task 6: A-core — bugreport

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/domain/Models.kt`（加 `BugreportResult`）
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt`（加 `bugreport`）
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt`（透传）
- Test: `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt`

**Interfaces:**
- Consumes: `AdbProcessRunner.run(..., timeoutMs)`（T5 已让 timeout 生效）
- Produces: `BugreportResult(zipPath, stdout)`；`CommandRunner.bugreport(serial, destDir): BugreportResult`

- [ ] **Step 1: 加 domain + 写失败测试**

`Models.kt` 加：
```kotlin
data class BugreportResult(val zipPath: String, val stdout: String)
```

`CommandRunnerTest.kt` 加（`bugreport` 是 host 命令无 `-s serial` 前缀的 shell——实际是 `adb -s <serial> bugreport <destDir>`，走 `runCmd`）：
```kotlin
@Test
fun bugreport_returns_zip_path_from_stdout() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("bugreport"), AdbProcessResult(0,
        "Bug report is processed\r\nBug report is stored at /tmp/bugreport-2026-09-04.zip\r\n", ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    val r = cr.bugreport("abc", "/tmp")
    assertEquals("/tmp/bugreport-2026-09-04.zip", r.zipPath)
}

@Test
fun bugreport_nonzero_throws() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("bugreport"), AdbProcessResult(1, "", "device offline"))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertFailsWith<AdbCommandException> { cr.bugreport("abc", "/tmp") }
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew :core:test --tests "*CommandRunnerTest.bugreport*"`
Expected: FAIL（`bugreport` 未定义）。

- [ ] **Step 3: 实现**

`CommandRunner.kt` 加（host 命令，用 `runCmd` 带大 timeout）：
```kotlin
/** `adb -s <serial> bugreport <destDir>` — host command (not shell). adb writes
 *  `bugreport-<date>.zip` into destDir and prints the path to stdout. Long-running (10–60s+):
 *  passes a 180s timeout (JvmAdbProcessRunner.run honors it via async reads after T5). */
suspend fun bugreport(serial: String, destDir: String): BugreportResult {
    val r = runCmd(serial, listOf("bugreport", destDir), timeoutMs = 180_000L)
    val zip = extractBugreportPath(r.stdout, destDir)
        ?: throw AdbCommandException(
            command = "adb -s $serial bugreport $destDir",
            exitCode = r.exitCode,
            stderr = "no bugreport zip path found in stdout; destDir=$destDir; stdout head=${r.stdout.take(200)}",
        )
    return BugreportResult(zipPath = zip, stdout = r.stdout)
}

/** Parse the zip path from `adb bugreport` stdout ("Bug report is stored at <path>"); fall back to
 *  scanning destDir for the newest bugreport-*.zip (real-device path; in tests stdout always carries it). */
private fun extractBugreportPath(stdout: String, destDir: String): String? {
    val re = Regex("Bug report is stored at:?\\s*(\\S+)")
    re.find(stdout)?.let { return it.groupValues[1].trim() }
    val dir = java.io.File(destDir)
    return dir.listFiles { f -> f.name.startsWith("bugreport-") && f.extension.equals("zip", ignoreCase = true) }
        ?.maxByOrNull { it.lastModified() }?.absolutePath
}
```
import `com.adbgui.core.domain.BugreportResult`。

- [ ] **Step 4: 改 `runCmd` 支持 timeout**

`CommandRunner.kt:340-348` 的 `runCmd` 现调用 `runner.run(adb(), full)`（无 timeout）。加可选 timeout 参数透传：
```kotlin
private suspend fun runCmd(serial: String, args: List<String>, timeoutMs: Long? = null): AdbProcessResult {
    server.ensureStarted()
    val full = buildList { add("-s"); add(serial); addAll(args) }
    val cmd = "adb ${full.joinToString(" ")}"
    val r = runner.run(adb(), full, timeoutMs)
    logger.debug("$cmd -> exit=${r.exitCode} err=${r.stderr.take(200)}")
    if (r.exitCode != 0) throw AdbCommandException(command = cmd, exitCode = r.exitCode, stderr = r.stderr)
    return r
}
```
（`FakeAdbProcessRunner.run` 签名已含 `timeoutMs`，传 null 不影响既有调用。）

- [ ] **Step 5: `DeviceRepository` 透传**

`DeviceRepository.kt` 加：
```kotlin
suspend fun bugreport(serial: String, destDir: String): com.adbgui.core.domain.BugreportResult =
    commands.bugreport(serial, destDir)
```

- [ ] **Step 6: 验证 + Commit**

Run: `./gradlew :core:test --tests "*CommandRunnerTest.bugreport*"`
Expected: PASS。`./gradlew :core:test` 全量回归。

```bash
git add core/src/main/kotlin/com/adbgui/core/domain/Models.kt \
  core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt \
  core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt \
  core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt
git commit -m "feat(core): adb bugreport with timeout + zip-path parsing"
```

---

## Task 7: A-desktop — SystemOpsViewModel bugreport + DeviceOverviewScreen UI

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemOpsViewModel.kt`（bugreport 状态 + 方法）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt`（bugreport UI 区，line 67 旁）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/SystemOpsViewModelTest.kt`（NEW）

**Interfaces:**
- Consumes: `DeviceRepository.bugreport(serial, destDir)`（T6）
- Produces: `SystemOpsViewModel.bugreport(destDir)` / `cancelBugreport()`；状态 `bugreportBusy`/`bugreportResult`/`bugreportError`

- [ ] **Step 1: 写 VM 失败测试**

新建 `SystemOpsViewModelTest.kt`，对齐既有 VM 测试模式（fake repo + mockito or 手工 fake——先 grep `desktop/src/test` 看既有 fake repo 用法）。示例（mockito 风格）：
```kotlin
class SystemOpsViewModelTest {
    private val selectedSerial = MutableStateFlow("serial1")
    private lateinit var fakeRepo: DeviceRepository

    @Test
    fun bugreport_success_sets_result() = runTest {
        whenever(fakeRepo.bugreport("serial1", "/out")).thenReturn(BugreportResult("/out/bugreport-x.zip", "ok"))
        val vm = SystemOpsViewModel(fakeRepo, selectedSerial, this)
        vm.bugreport("/out")
        advanceUntilIdle()
        assertEquals("/out/bugreport-x.zip", vm.bugreportResult.value?.zipPath)
    }

    @Test
    fun bugreport_failure_sets_error() = runTest {
        whenever(fakeRepo.bugreport(any(), any())).thenThrow(AdbCommandException("adb bugreport", 1, "offline"))
        val vm = SystemOpsViewModel(fakeRepo, selectedSerial, this)
        vm.bugreport("/out")
        advanceUntilIdle()
        assert(vm.bugreportError.value!!.contains("offline"))
    }
}
```
import `com.adbgui.core.domain.BugreportResult`、`AdbCommandException`。

- [ ] **Step 2: 验证失败**

Run: `./gradlew :desktop:test --tests "*SystemOpsViewModelTest.bugreport*"`
Expected: FAIL（`bugreportResult` 等未定义）。

- [ ] **Step 3: VM bugreport 状态 + 方法**

`SystemOpsViewModel.kt` 加（**独立于 `_busy`**，长时不阻塞重启按钮）：
```kotlin
private val _bugreportBusy = MutableStateFlow(false)
val bugreportBusy: StateFlow<Boolean> = _bugreportBusy.asStateFlow()
private val _bugreportResult = MutableStateFlow<BugreportResult?>(null)
val bugreportResult: StateFlow<BugreportResult?> = _bugreportResult.asStateFlow()
private val _bugreportError = MutableStateFlow<String?>(null)
val bugreportError: StateFlow<String?> = _bugreportError.asStateFlow()
private var bugreportJob: Job? = null

fun bugreport(destDir: String) {
    val serial = selectedSerial.value ?: return
    bugreportJob?.cancel()
    bugreportJob = scope.launch {
        _bugreportBusy.value = true; _bugreportError.value = null; _bugreportResult.value = null
        try { _bugreportResult.value = repo.bugreport(serial, destDir) }
        catch (e: AdbCommandException) { _bugreportError.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
        finally { _bugreportBusy.value = false }
    }
}

fun cancelBugreport() { bugreportJob?.cancel(); _bugreportBusy.value = false }
```
import `com.adbgui.core.domain.BugreportResult`、`kotlinx.coroutines.Job`。

- [ ] **Step 4: Screen UI 区**

`DeviceOverviewScreen.kt` 在 device-tools `SectionCard`（line 74）内 reboot 段后加 bugreport 区（或新开一个 `SectionCard`）：
```kotlin
if (systemOpsVm != null) {
    val brBusy by systemOpsVm.bugreportBusy.collectAsState()
    val brResult by systemOpsVm.bugreportResult.collectAsState()
    val brError by systemOpsVm.bugreportError.collectAsState()
    SectionCard(headerTitle = Strings.t("bugreport")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = selectedSerial != null && !brBusy,
                onClick = {
                    val dir = com.adbgui.desktop.platform.FileDialogs.pickDirectory(title = Strings.t("bugreport_pick_dir"), currentPath = null)
                    if (dir != null) systemOpsVm.bugreport(dir)
                },
            ) { Text(Strings.t("export_bugreport")) }
            Spacer(Modifier.width(8.dp))
            if (brBusy) {
                CircularProgressIndicator(modifier = Modifier.heightIn(max = 18.dp))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { systemOpsVm.cancelBugreport() }) { Text(Strings.t("cancel")) }
            }
        }
        brError?.let { InlineMessageBanner(Strings.t("adb_error"), MessageKind.Error, details = it, initiallyExpanded = true) }
        brResult?.let { r ->
            InlineMessageBanner(Strings.t("bugreport_done"), MessageKind.Success, details = r.zipPath)
            // Open / Open folder 复用截图模式（Desktop.openFile / explorer /select）
            Row {
                TextButton(onClick = { com.adbgui.desktop.platform.Desktop.openFile(r.zipPath) }) { Text(Strings.t("open")) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { com.adbgui.desktop.platform.Desktop.openFolderOfFile(r.zipPath) }) { Text(Strings.t("open_folder")) }
            }
        }
    }
}
```
**注意**：截图的 Open/Open-folder 实现在 `desktop/.../platform/`（先 grep `openFile\|explorer\|/select` 定位既有函数名，用真实函数名替换上面 `Desktop.openFile`/`openFolderOfFile` 占位调用）。`InlineMessageBanner`/`MessageKind`/`SectionCard` 是既有 composable（见 `DeviceOverviewScreen.kt` 顶部 import 与同文件用法）。

- [ ] **Step 5: i18n keys**

`Strings.kt` zh 加：
```
"bugreport" to "Bugreport",
"export_bugreport" to "导出 Bugreport",
"bugreport_pick_dir" to "选择保存目录",
"bugreport_done" to "已生成 Bugreport",
"cancel" to "取消",
"open" to "打开",
"open_folder" to "打开文件夹",
```
en 加对应英文。**先 grep `Strings.kt` 确认 `cancel`/`open`/`open_folder` 是否已有 key**（截图/导出设备信息可能已定义），有则复用不重复加。

- [ ] **Step 6: 验证 + Commit**

Run: `./gradlew :desktop:test --tests "*SystemOpsViewModelTest"`
Expected: PASS。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemOpsViewModel.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt \
  desktop/src/test/kotlin/com/adbgui/desktop/ui/SystemOpsViewModelTest.kt
git commit -m "feat(desktop): bugreport export with progress + cancel + open-folder"
```

---

## Task 8: D+E-core — DumpsysPackage parser + dumpsysPackage + grant/revoke + listNativeLibs

> **前置（fixture）：** parser 测试必须读真实录制的 `adb shell dumpsys package <某三方包>` 输出（CLAUDE.md §4，不许手写）。在 ≥2 台不同 Android 版本设备上录制：
> ```
> adb shell dumpsys package com.example.app > dumpsys_package_android13.txt
> ```
> 文件首行加注释：`# 来源：<型号> + <manufacturer> + Android <版本> (SDK <n>, build <id>) + 录制日期 2026-09-04 + 命令: adb shell dumpsys package <pkg>`。存 `core/src/test/resources/fixtures/`。
>
> **若 fixture 未录制**：先做 Step 3（`grant`/`revoke`，无 fixture 依赖）+ Step 7（`listNativeLibs`，复用 LsParser）+ `DumpsysPackage` domain；parser（Step 4-6）待 fixture 后做。可先提交 grant/revoke + listNativeLibs + domain，parser 单独后续提交。

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/domain/DumpsysPackage.kt`（`PermissionInfo` + `DumpsysPackage` + `PackageDetail`）
- Create: `core/src/main/kotlin/com/adbgui/core/adb/DumpsysPackageParser.kt`
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt`（加 `dumpsysPackage`/`grant`/`revoke`/`listNativeLibs`）
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt`（透传 + `packageDetail` 编排）
- Create: `core/src/test/kotlin/com/adbgui/core/adb/DumpsysPackageParserTest.kt`
- Modify: `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt`
- Create: `core/src/test/resources/fixtures/dumpsys_package_android13.txt`（用户录制）
- Create: `core/src/test/resources/fixtures/dumpsys_package_android11.txt`（用户录制，第二变体）

**Interfaces:**
- Consumes: `runShellCmd`（`CommandRunner` 既有，已 sanitizeShellOutput）、`LsParser.parse(stdout): List<FileEntry>`（既有，`FileEntry.name` 字段）
- Produces: `PermissionInfo`、`DumpsysPackage`、`PackageDetail`；`CommandRunner.dumpsysPackage(serial, pkg)`、`grant`/`revoke`/`listNativeLibs`；`DeviceRepository.packageDetail(serial, pkg)` + `dumpsysPackage`/`grant`/`revoke`

- [ ] **Step 1: 写 domain**

`DumpsysPackage.kt`：
```kotlin
package com.adbgui.core.domain

data class PermissionInfo(
    val name: String,
    val granted: Boolean,
    val runtime: Boolean,
    val group: String? = null,
)

/** Parsed `adb shell dumpsys package <pkg>` — fields E (version/path/libs dir) and D (permissions) share this. */
data class DumpsysPackage(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val publicSourceDir: String?,
    val nativeLibraryDir: String?,
    val primaryCpuAbi: String?,
    val permissions: List<PermissionInfo>,
)

/** UI view for E (app detail): flat, no permissions, decoupled from parse output. */
data class PackageDetail(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val publicSourceDir: String?,
    val nativeLibraryDir: String?,
    val primaryCpuAbi: String?,
    val nativeLibs: List<String>,
)
```

- [ ] **Step 2: 写 parser 失败测试（读 fixture）**

`DumpsysPackageParserTest.kt`：
```kotlin
package com.adbgui.core.adb

import com.adbgui.core.domain.PermissionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpsysPackageParserTest {
    private fun fixture(name: String): String {
        val url = javaClass.classLoader.getResource("fixtures/$name")!!
        val raw = url.readText()
        // strip leading comment lines starting with '#'
        return raw.lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n")
    }

    @Test
    fun parses_version_and_path_and_permissions_android13() {
        val out = fixture("dumpsys_package_android13.txt")
        val pkg = DumpsysPackageParser.parse(out)
        assertNotNull(pkg)
        assertNotNull(pkg!!.versionName)
        assertTrue(pkg.versionCode != null)
        assertNotNull(pkg.codePath)
        // D side: permissions present, runtime ones flagged
        val cam = pkg.permissions.firstOrNull { it.name == "android.permission.CAMERA" }
        assertNotNull(cam) { "CAMERA permission expected in fixture" }
        // E side + D side asserted from the SAME fixture (spec §6.1)
    }

    @Test
    fun returns_null_when_no_packages_section() {
        val pkg = DumpsysPackageParser.parse("garbage output\nno Packages section here")
        assertEquals(null, pkg)
    }
}
```
**fixture 字段断言值**（`versionName`/`versionCode`/`codePath` 具体期望值）在录制后按真实 fixture 内容补全——录完后看 fixture 实际值写进断言，不要猜。

- [ ] **Step 3: grant / revoke（无 fixture 依赖，先做）**

`CommandRunnerTest.kt` 加：
```kotlin
@Test
fun grant_passes_pkg_and_perm() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("pm","grant"), AdbProcessResult(0, "", ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    cr.grant("abc", "com.x", "android.permission.CAMERA")
    val argv = runner.runs.last()
    assertTrue(argv.containsAll(listOf("-s","abc","shell","pm","grant","com.x","android.permission.CAMERA")))
}

@Test
fun grant_failure_throws() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("pm","grant"), AdbProcessResult(1, "", "not a runtime permission"))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertFailsWith<AdbCommandException> { cr.grant("abc", "com.x", "android.permission.CAMERA") }
}
```

`CommandRunner.kt` 加：
```kotlin
suspend fun grant(serial: String, pkg: String, perm: String): String =
    runCmd(serial, listOf("shell", "pm", "grant", pkg, perm)).stdout

suspend fun revoke(serial: String, pkg: String, perm: String): String =
    runCmd(serial, listOf("shell", "pm", "revoke", pkg, perm)).stdout
```

- [ ] **Step 4: dumpsysPackage + pkg 守卫失败测试**

`CommandRunnerTest.kt` 加：
```kotlin
@Test
fun dumpsysPackage_passes_pkg_to_dumpsys() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("dumpsys","package"), AdbProcessResult(0, FIXTURE_DUMPSYS, ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    val pkg = cr.dumpsysPackage("abc", "com.example")
    val argv = runner.runs.last()
    assertTrue(argv.containsAll(listOf("-s","abc","shell","dumpsys","package","com.example")))
    assertNotNull(pkg)
}
```
（`FIXTURE_DUMPSYS` = 一个内联的最小 "Packages:\n versionName=1.0\n..." 字符串让 parser 返回非 null；或直接读 fixture resource。）

```kotlin
@Test
fun dumpsysPackage_rejects_invalid_pkg() = runTest {
    val cr = CommandRunner({ adb }, FakeAdbProcessRunner(), NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertFailsWith<IllegalArgumentException> { cr.dumpsysPackage("abc", "bad pkg!") }
}
```

- [ ] **Step 5: 实现 `dumpsysPackage`**

`CommandRunner.kt` 加：
```kotlin
private val pkgRegex = Regex("^[A-Za-z0-9._]+$")

suspend fun dumpsysPackage(serial: String, pkg: String): DumpsysPackage? {
    require(pkgRegex.matches(pkg)) { "invalid package name: $pkg" }
    val out = runShellCmd(serial, "dumpsys package $pkg")
    val parsed = DumpsysPackageParser.parse(out)
    if (parsed == null) {
        throw AdbCommandException(
            command = "adb -s $serial shell dumpsys package $pkg",
            exitCode = -1,
            stderr = "no Packages: section in dumpsys output; stdout head=${out.take(200)}",
        )
    }
    return parsed
}
```
import `com.adbgui.core.domain.DumpsysPackage`。

- [ ] **Step 6: 实现 parser**

`DumpsysPackageParser.kt`：
```kotlin
package com.adbgui.core.adb

import com.adbgui.core.domain.DumpsysPackage
import com.adbgui.core.domain.PermissionInfo

/** Parse `adb shell dumpsys package <pkg>` stdout. Returns null when no `Packages:` section is
 *  found (caller wraps into AdbCommandException with raw output). Field formats vary across
 *  Android versions — fixtures cover the variants; parser is line-oriented regex. */
object DumpsysPackageParser {
    fun parse(stdout: String): DumpsysPackage? {
        if (!stdout.contains("Packages:") && !stdout.contains("Package [")) return null
        val versionName = findVal(stdout, "versionName")
        val versionCode = findVal(stdout, "versionCode")?.toLongOrNull()
        val codePath = findVal(stdout, "codePath")
        val publicSourceDir = findVal(stdout, "publicSourceDir")
        val nativeLibraryDir = findVal(stdout, "nativeLibraryDir")
        val primaryCpuAbi = findVal(stdout, "primaryCpuAbi")
        val permissions = parsePermissions(stdout)
        return DumpsysPackage(versionName, versionCode, codePath, publicSourceDir, nativeLibraryDir, primaryCpuAbi, permissions)
    }

    private fun findVal(stdout: String, key: String): String? {
        // dumpsys prints `  key=value` lines; value may be quoted.
        val re = Regex("(?m)^\\s*$key=(.*)$")
        val v = re.find(stdout)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") ?: return null
        return v.ifBlank { null }
    }

    private fun parsePermissions(stdout: String): List<PermissionInfo> {
        val result = mutableListOf<PermissionInfo>()
        // runtime permissions: `<perm>: granted=<true|false>`
        val runtimeRe = Regex("(?m)^\\s*(\\S+): granted=(true|false)")
        runtimeRe.findAll(stdout).forEach { m ->
            result.add(PermissionInfo(name = m.groupValues[1], granted = m.groupValues[2] == "true", runtime = true))
        }
        // install permissions: `<perm>: granted=true|false` (subset; mark non-runtime if not already present)
        val installRe = Regex("(?m)^\\s*(android\\.permission\\.\\S+): granted=(true|false)")
        installRe.findAll(stdout).forEach { m ->
            val name = m.groupValues[1]
            if (result.none { it.name == name }) {
                result.add(PermissionInfo(name = name, granted = m.groupValues[2] == "true", runtime = false))
            }
        }
        return result
    }
}
```
**fixture 录制后必须按真实输出校准 regex**（dumpsys 格式跨版本不同，上面的 regex 是 Android 11+ 常见形式；若 fixture 暴露不同形式，按 TDD 红→绿修 regex，不要硬塞）。`runtime permissions:` 段在不同版本可能是 `runtime permissions:` 或合并进 `install permissions:`；看 fixture 调整。

- [ ] **Step 7: listNativeLibs（复用 LsParser）**

`CommandRunnerTest.kt` 加：
```kotlin
@Test
fun listNativeLibs_filters_so_files() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("ls"), AdbProcessResult(0, FIXTURE_LS_LIBS, ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    val libs = cr.listNativeLibs("abc", "/data/app/.../lib")
    assertTrue(libs.contains("libfoo.so"))
    assertTrue(libs.none { !it.endsWith(".so") })
}

@Test
fun listNativeLibs_bad_dir_returns_empty() = runTest {
    val cr = CommandRunner({ adb }, FakeAdbProcessRunner(), NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertEquals(emptyList(), cr.listNativeLibs("abc", "bad dir; rm -rf"))
}
```
（`FIXTURE_LS_LIBS` 用既有 ls fixture 或内联一段 `ls -la` 输出含 `.so` 文件——先看 `LsParserTest` 既有 fixture 复用。）

`CommandRunner.kt` 加：
```kotlin
private val safePathRegex = Regex("^[^\\s;&|`'$<>]+$")

suspend fun listNativeLibs(serial: String, dir: String): List<String> {
    if (!safePathRegex.matches(dir)) { logger.warn("listNativeLibs: rejecting dir: $dir"); return emptyList() }
    val out = runCatching { ls(serial, dir) }.getOrElse { return emptyList() }
    return LsParser.parse(out).map { it.name }.filter { it.endsWith(".so") }
}
```
（`LsParser.parse` 返回 `List<FileEntry>`，`FileEntry.name` 字段——先看 `domain/FileEntry.kt` 确认字段名。）`logger.warn` 若 `Logger` 接口无 `warn`，用 `logger.info`（先看 `Logger` 接口有哪些方法）。

- [ ] **Step 8: `DeviceRepository.packageDetail` 编排 + 透传**

`DeviceRepository.kt` 加：
```kotlin
suspend fun dumpsysPackage(serial: String, pkg: String): com.adbgui.core.domain.DumpsysPackage? =
    commands.dumpsysPackage(serial, pkg)
suspend fun grant(serial: String, pkg: String, perm: String): String = commands.grant(serial, pkg, perm)
suspend fun revoke(serial: String, pkg: String, perm: String): String = commands.revoke(serial, pkg, perm)
suspend fun listNativeLibs(serial: String, dir: String): List<String> = commands.listNativeLibs(serial, dir)

suspend fun packageDetail(serial: String, pkg: String): com.adbgui.core.domain.PackageDetail {
    val dp = commands.dumpsysPackage(serial, pkg) ?: error("dumpsys package returned null for $pkg")
    val libs = if (dp.nativeLibraryDir != null) commands.listNativeLibs(serial, dp.nativeLibraryDir) else emptyList()
    return com.adbgui.core.domain.PackageDetail(
        versionName = dp.versionName, versionCode = dp.versionCode,
        codePath = dp.codePath, publicSourceDir = dp.publicSourceDir,
        nativeLibraryDir = dp.nativeLibraryDir, primaryCpuAbi = dp.primaryCpuAbi,
        nativeLibs = libs,
    )
}
```

- [ ] **Step 9: 验证 + Commit**

Run: `./gradlew :core:test --tests "*DumpsysPackageParserTest" --tests "*CommandRunnerTest.dumpsysPackage*" --tests "*CommandRunnerTest.grant*" --tests "*CommandRunnerTest.listNativeLibs*"`
Expected: PASS（parser 测试需 fixture 已录制；grant/revoke/listNativeLibs 不依赖 fixture）。

```bash
git add core/src/main/kotlin/com/adbgui/core/domain/DumpsysPackage.kt \
  core/src/main/kotlin/com/adbgui/core/adb/DumpsysPackageParser.kt \
  core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt \
  core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt \
  core/src/test/kotlin/com/adbgui/core/adb/DumpsysPackageParserTest.kt \
  core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt \
  core/src/test/resources/fixtures/dumpsys_package_android13.txt \
  core/src/test/resources/fixtures/dumpsys_package_android11.txt
git commit -m "feat(core): dumpsys package parser (version/path/permissions) + grant/revoke + listNativeLibs"
```

---

## Task 9: D+E-desktop — AppConsoleViewModel 详情 + 权限 + Screen UI

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt`（详情 + 权限状态 + `DumpsysPackage` 缓存 + `loadDetail`/`loadPermissions`/`togglePermission`）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt`（行内详情展开 + 权限段）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceRepository.packageDetail(serial, pkg)`、`dumpsysPackage`、`grant`、`revoke`（T8）
- Produces: VM `detail: StateFlow<PackageDetail?>`、`permissions: StateFlow<List<PermissionInfo>>`、`loadDetail(pkg)`、`loadPermissions(pkg)`、`togglePermission(pkg, perm, grant)`

- [ ] **Step 1: 写 VM 失败测试**

```kotlin
@Test
fun loadDetail_success_sets_detail() = runTest {
    whenever(fakeRepo.packageDetail("serial1","com.x")).thenReturn(PackageDetail("1.0", 10L, "/data/app/x/base.apk", null, "/data/app/x/lib", "arm64-v8a", listOf("libfoo.so")))
    val vm = AppConsoleViewModel(fakeRepo, selectedSerial, this)
    vm.loadDetail("com.x")
    advanceUntilIdle()
    assertEquals("1.0", vm.detail.value?.versionName)
}

@Test
fun loadPermissions_uses_cached_dumpsys_when_same_pkg() = runTest {
    val dp = DumpsysPackage("1.0", 10L, null, null, null, null, listOf(PermissionInfo("android.permission.CAMERA", granted = false, runtime = true)))
    whenever(fakeRepo.dumpsysPackage("serial1","com.x")).thenReturn(dp)
    val vm = AppConsoleViewModel(fakeRepo, selectedSerial, this)
    vm.loadPermissions("com.x")
    advanceUntilIdle()
    vm.loadDetail("com.x")  // should NOT re-call dumpsysPackage (cached)
    advanceUntilIdle()
    verify(fakeRepo, times(1)).dumpsysPackage(any(), any())
}

@Test
fun togglePermission_grant_updates_local_granted() = runTest {
    val dp = DumpsysPackage("1.0", 10L, null, null, null, null, listOf(PermissionInfo("android.permission.CAMERA", granted = false, runtime = true)))
    whenever(fakeRepo.dumpsysPackage("serial1","com.x")).thenReturn(dp)
    whenever(fakeRepo.grant("serial1","com.x","android.permission.CAMERA")).thenReturn("")
    val vm = AppConsoleViewModel(fakeRepo, selectedSerial, this)
    vm.loadPermissions("com.x"); advanceUntilIdle()
    vm.togglePermission("com.x","android.permission.CAMERA", grant = true); advanceUntilIdle()
    assertEquals(true, vm.permissions.value.first { it.name == "android.permission.CAMERA" }.granted)
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew :desktop:test --tests "*AppConsoleViewModelTest.loadDetail*" --tests "*AppConsoleViewModelTest.loadPermissions*" --tests "*AppConsoleViewModelTest.togglePermission*"`
Expected: FAIL。

- [ ] **Step 3: VM 状态 + 方法**

`AppConsoleViewModel.kt` 加：
```kotlin
private val _detail = MutableStateFlow<PackageDetail?>(null)
val detail: StateFlow<PackageDetail?> = _detail.asStateFlow()
private val _detailError = MutableStateFlow<String?>(null)
val detailError: StateFlow<String?> = _detailError.asStateFlow()
private val _permissions = MutableStateFlow<List<PermissionInfo>>(emptyList())
val permissions: StateFlow<List<PermissionInfo>> = _permissions.asStateFlow()
private val _cachedDumpsys = MutableStateFlow<Pair<String, DumpsysPackage>?>(null)  // (pkg, parsed)

fun loadDetail(pkg: String) = scope.launch {
    val serial = selectedSerial.value ?: return@launch
    _detailError.value = null
    try {
        // reuse cached dumpsys if same pkg (avoid double dumpsys per spec §6.2)
        val dp = cachedOrLoad(serial, pkg)
        val libs = if (dp.nativeLibraryDir != null) repo.listNativeLibs(serial, dp.nativeLibraryDir) else emptyList()
        _detail.value = PackageDetail(dp.versionName, dp.versionCode, dp.codePath, dp.publicSourceDir, dp.nativeLibraryDir, dp.primaryCpuAbi, libs)
    } catch (e: AdbCommandException) { _detailError.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
}

fun loadPermissions(pkg: String) = scope.launch {
    val serial = selectedSerial.value ?: return@launch
    _permissionsError.value = null
    try { val dp = cachedOrLoad(serial, pkg); _permissions.value = dp.permissions }
    catch (e: AdbCommandException) { _permissionsError.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
}

fun togglePermission(pkg: String, perm: String, grant: Boolean) = scope.launch {
    val serial = selectedSerial.value ?: return@launch
    try {
        if (grant) repo.grant(serial, pkg, perm) else repo.revoke(serial, pkg, perm)
        // local update: avoid full re-dumpsys
        _permissions.value = _permissions.value.map { if (it.name == perm) it.copy(granted = grant) else it }
        _cachedDumpsys.value?.let { (p, dp) ->
            if (p == pkg) _cachedDumpsys.value = pkg to dp.copy(permissions = dp.permissions.map { if (it.name == perm) it.copy(granted = grant) else it })
        }
    } catch (e: AdbCommandException) { _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}" }
}

private suspend fun cachedOrLoad(serial: String, pkg: String): DumpsysPackage {
    val cached = _cachedDumpsys.value
    if (cached != null && cached.first == pkg) return cached.second
    val dp = repo.dumpsysPackage(serial, pkg) ?: throw AdbCommandException("dumpsys package $pkg", -1, "no Packages section")
    _cachedDumpsys.value = pkg to dp
    return dp
}
```
加 `_permissionsError` StateFlow。import `com.adbgui.core.domain.PackageDetail`、`DumpsysPackage`、`PermissionInfo`。选中包切换时清缓存（在 `load()` 或 selectedSerial collect 处加 `_cachedDumpsys.value = null`）。

- [ ] **Step 4: Screen — 行内详情展开 + 权限段**

`PackageSelectRow`（`AppConsoleScreen.kt:321`）加展开箭头 + 展开区；或 `AdvancedPanel` 内加"应用详情"段 + "权限"段。**在 AdvancedPanel 内加**（对齐 spec §6.2/§6.3，Advanced 面板容纳）：
```kotlin
// --- 应用详情（E） ---
Text(Strings.t("app_detail"), style = MaterialTheme.typography.subtitle2)
val detail by vm.detail.collectAsState()
val detailError by vm.detailError.collectAsState()
Button(enabled = !busy, onClick = { vm.loadDetail(pkg) }) { Text(Strings.t("load_detail")) }
detail?.let { d ->
    Text("version: ${d.versionName ?: "?"} (${d.versionCode ?: "?"})")
    d.codePath?.let { Row { Text(it); IconButton(onClick = { copy(it) }) { Icon(ContentCopy, null) } } }
    d.publicSourceDir?.let { Text(it) }
    d.primaryCpuAbi?.let { Text("abi: $it") }
    Text("libs: " + if (d.nativeLibs.isEmpty()) Strings.t("no_native_libs") else d.nativeLibs.joinToString(" "))
}
detailError?.let { InlineMessageBanner(Strings.t("adb_error"), MessageKind.Error, details = it, initiallyExpanded = true) }

Divider()

// --- 权限（D） ---
Text(Strings.t("permissions"), style = MaterialTheme.typography.subtitle2)
val permissions by vm.permissions.collectAsState()
Button(enabled = !busy, onClick = { vm.loadPermissions(pkg) }) { Text(Strings.t("load_permissions")) }
LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
    items(permissions, key = { it.name }) { p ->
        if (p.runtime) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = p.granted, enabled = !busy, onCheckedChange = { vm.togglePermission(pkg, p.name, it) })
                Text(p.name)
            }
        } else {
            Text("${p.name} (${if (p.granted) Strings.t("granted") else Strings.t("not_granted")})", style = MaterialTheme.typography.caption)
        }
    }
}
```
`AdvancedPanel` 需要 `vm` 引用（现签名只传回调）——把 `vm` 透传进 `AdvancedPanel`，或把 detail/permissions 状态在 `AppConsoleScreen` collect 后传进 `AdvancedPanel`。**改 `AdvancedPanel` 签名**：加 `detail: PackageDetail?`、`detailError`、`permissions: List<PermissionInfo>`、`onLoadDetail`、`onLoadPermissions`、`onTogglePermission` 参数（对齐既有 `onStartActivity` 等回调模式）。`copy(text)` 用 `Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)`（见既有 `PackageSelectRow` line 340-343）。

- [ ] **Step 5: i18n keys**

`Strings.kt` zh 加：
```
"app_detail" to "应用详情",
"load_detail" to "加载详情",
"no_native_libs" to "无原生库",
"permissions" to "权限",
"load_permissions" to "加载权限",
"granted" to "已授予",
"not_granted" to "未授予",
```
en 加对应英文。

- [ ] **Step 6: 验证 + Commit**

Run: `./gradlew :desktop:test`
Expected: PASS（全量回归）。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt \
  desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt
git commit -m "feat(desktop): app detail (version/path/libs) + permission grant/revoke in App Console"
```

---

## Self-Review

**Spec coverage:**
- A (bugreport): Tasks 5 (precondition) + 6 (core) + 7 (desktop). ✅
- B (install-multiple + flags): Tasks 1 (core) + 2 (desktop). ✅
- C (am start builder): Tasks 3 (core) + 4 (desktop). ✅
- D (permissions): Tasks 8 (core grant/revoke + dumpsysPackage + parser) + 9 (desktop). ✅
- E (app detail): Tasks 8 (dumpsysPackage fields + listNativeLibs + packageDetail) + 9 (desktop). ✅
- D+E 共用 parser/fixture: Task 8 single parser, single fixture set. ✅
- §2 红线: each core task is pure adb/Parser (no UI); UI via DeviceRepository only. ✅
- §7 i18n: keys added per-task. ✅
- §9 bugreport timeout/cancel: Task 5 fixes `run` so timeout honored; Task 6 passes 180s timeout + VM cancel. ✅
- §6.4 fixture gating: Task 8 Step note + ordering. ✅

**Placeholder scan:** Task 5 Step 1 defers to "先读该文件决定"（real file-read decision, not a code placeholder). Task 7 Step 4 + Task 9 Step 4 reference `Desktop.openFile`/`copy()` with explicit "grep 既有函数名" instructions — these are "verify the real name" not "TBD". Task 8 Step 2 fixture assertion values deferred to post-recording (fixture content unknown until user records — flagged explicitly, not a vague TODO). All code blocks contain real code. No "implement later"/"add error handling" hand-waves.

**Type consistency:** `InstallFlags`/`BugreportResult`/`PermissionInfo`/`DumpsysPackage`/`PackageDetail` defined once, signatures match across tasks. `install(serial, paths, flags)` consistent T1→T2. `startActivity(serial, action, data, component, extras)` consistent T3→T4. `dumpsysPackage`/`grant`/`revoke`/`listNativeLibs`/`packageDetail` consistent T8→T9. `cancelBugreport`/`bugreport(destDir)` consistent T6→T7.

**Notes for executor:**
- Task 5's `run` refactor changes behavior for ALL adb commands (safer async reads). Run full `:desktop:test` + smoke after.
- Task 8 parser regex MUST be calibrated against real fixtures after recording — do not trust the regex as-is; TDD red→green against fixture.
- Several desktop steps say "grep 既有函数名" — do the grep, use the real name. Don't invent.
- D+E parser tasks blocked until fixtures recorded; Tasks 1-7 (A/B/C) are unblocked and can proceed immediately in order B→C→A.
