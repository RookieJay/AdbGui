# Logcat AS 式过滤（包名 / Tag / 右键 / 持久化）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Logcat 页增加 AS 式过滤：包名下拉（pidof 解析）、Tag 下拉 + 多重排除、日志行右键快捷过滤、过滤条件持久化。

**Architecture:** ring 全量缓存 + 客户端过滤不变。包名→pid 映射用 `adb shell pidof`，controller 常驻循环每 5s 重解析、pid 只增不减；pids 放进 `LogcatFilters` 值对象（dumpLogcat 导出复用同一谓词）。UI 过滤栏拆出 `LogcatFilterBar.kt`。

**Tech Stack:** Kotlin / Compose Multiplatform (Material)、kotlinx.coroutines、kotlinx.serialization、JUnit + kotlin.test。

**Spec:** `docs/superpowers/specs/2026-10-14-logcat-advanced-filters-design.md`

## Global Constraints

- `:core` 不依赖任何 UI；`:desktop` UI 不直接碰 adb/CommandRunner（只经 ViewModel → DeviceRepository/controller）。
- **`:core` TDD**：先写失败测试（含 fixture）→ 验证失败 → 最小实现 → 验证通过 → 提交。
- **fixture 必须真实录制**（真机 `adb shell pidof ...`），文件头注释标设备信息 + 命令 + 日期。
- `:core` I/O 注入 Dispatcher；跨线程可变 `var` 必须 `@Volatile` 或在 Mutex 内。
- 不留死代码：移除用法时同时删除声明（本计划移除 `LogcatFilters.pid`）。
- Conventional Commits：`feat(scope):` / `fix(scope):` / `docs:` / `chore:`。
- 测试命令用 `./gradlew :core:test --tests "..."`（Git Bash）。
- **测试时的关键陷阱**：`LogcatController` 新增的重解析循环是 `while(true){ ...; delay(reResolveIntervalMs) }`——只要循环活着，`runTest` 里的 `advanceUntilIdle()` 会**永远不返回**（无限重排的 delay 任务）。凡激活了包过滤的测试，结束前必须 `c.setFilters(LogcatFilters())` 停掉循环，且中途用 `runCurrent()` / `advanceTimeBy(n)`，不要 `advanceUntilIdle()`。

---

### Task 1: `LogcatFilters` 模型升级 + `matches()`（纯重构，先行）

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatController.kt`（`LogcatFilters` data class + `matches()`）
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatControllerTest.kt`

**Interfaces:**
- Consumes: 现有 `LogcatFilters(levelSet, tagInclude, tagExclude, text, textIsRegex, pid)`。
- Produces: `LogcatFilters(levelSet: Set<LogcatLevel>, packageName: String?, pids: Set<Int>, tagInclude: String?, tagExcludes: List<String>, text: String?, textIsRegex: Boolean)`——后续所有任务都构造这个形状。**注意**：此任务不做解析循环（Task 3），`packageName` 字段暂时只是数据。

- [ ] **Step 1: 更新既有测试 + 写新失败测试**

`LogcatControllerTest.kt` 里把 `setFilters_tag_text_pid_combine`（约 105 行）的 `pid = 100,` 改为 `pids = setOf(100),`。

新增两个测试（追加在 `setFilters_text_invalid_regex_falls_back_to_plain_contains` 之后）：

```kotlin
@Test fun setFilters_pids_filters_lines() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf(
        "08-17 10:23:45.100  100  200 I Tag: keep",
        "08-17 10:23:45.200  999  201 I Tag: drop",
    ))
    val c = controller(runner, this)
    c.start("abc"); advanceUntilIdle()
    c.setFilters(LogcatFilters(pids = setOf(100)))
    advanceUntilIdle()
    assertEquals(1, c.lines.value.size)
    assertEquals("keep", c.lines.value[0].message)
    c.stop()
}

@Test fun setFilters_tagExcludes_filters_multiple_case_insensitive_blank_ignored() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf(
        "08-17 10:23:45.100  100  200 I Noise: drop",
        "08-17 10:23:45.200  100  201 I noisy: drop case-insensitive",
        "08-17 10:23:45.300  100  202 I Signal: keep",
    ))
    val c = controller(runner, this)
    c.start("abc"); advanceUntilIdle()
    c.setFilters(LogcatFilters(tagExcludes = listOf("noise", "  ")))
    advanceUntilIdle()
    assertEquals(1, c.lines.value.size)
    assertEquals("keep", c.lines.value[0].message)
    c.stop()
}
```

- [ ] **Step 2: 运行确认编译失败（红）**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatControllerTest"`
Expected: **COMPILATION ERROR**——`pids`/`tagExcludes`/`packageName` 不存在，`pid =` 命名参数不存在。这是 TDD 的红。

- [ ] **Step 3: 最小实现**

`LogcatController.kt` 里替换 `LogcatFilters`（保持包内位置不变）：

```kotlin
data class LogcatFilters(
    val levelSet: Set<LogcatLevel> = LogcatLevel.entries.toSet(),
    /** 用户在下拉里选的应用；null = 无包过滤。非空时由 controller 的重解析循环维护 [pids]（Task 3）。 */
    val packageName: String? = null,
    /** 生效 pid 过滤（包的 pid 集合，或右键「只看此 PID」的单元素集合）。会话态，不持久化。 */
    val pids: Set<Int> = emptySet(),
    val tagInclude: String? = null,
    /** 多重排除：tag 命中任一（忽略大小写）即被滤掉。 */
    val tagExcludes: List<String> = emptyList(),
    val text: String? = null,
    /** When true, [text] is a regex (case-sensitive — embed `(?i)` for case-insensitive).
     * An invalid pattern falls back to plain-contains matching so the stream never dies. */
    val textIsRegex: Boolean = false,
)
```

`matches()` 里删除 `f.pid?.let { if (line.pid != it) return false }` 和 `f.tagExclude?.takeIf{...}` 两段，替换为（放在 level 检查之后）：

```kotlin
f.tagExcludes.forEach { ex ->
    if (ex.isNotBlank() && line.tag.contains(ex, ignoreCase = true)) return false
}
if (f.pids.isNotEmpty() && line.pid !in f.pids) return false
```

（`tagInclude` 的处理保持不变。）

- [ ] **Step 4: 全局搜引用，确认无死代码/漏改**

Run: `grep -rn "\.pid\b\|tagExclude\b\|LogcatFilters(" --include="*.kt" core/src desktop/src | grep -v LogcatLine`
Expected: `LogcatFilters(` 的调用点只用命名参数 `levelSet/text/textIsRegex`（LogcatScreen、tests）——命名参数所以不因删字段而断；`tagExclude`/`.pid`（filter 用途）零残留。若 LogcatScreen/VM 编译报错（不该有——都是命名参数），修复后继续。

- [ ] **Step 5: 运行测试确认通过 + 提交**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatControllerTest" :desktop:test --tests "com.adbgui.desktop.ui.LogcatViewModelTest"`
Expected: PASS（全部）。

```bash
git add -A && git commit -m "refactor(core): LogcatFilters gains packageName/pids/tagExcludes, drops pid/tagExclude"
```

---

### Task 2: `CommandRunner.pidOf` + 真实 fixture + Fake runner 规则替换

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/AdbProcessRunner.kt`（`FakeAdbProcessRunner.whenArgsContains` 支持同关键词替换）
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt`（新增 `pidOf`，放在 `listPackages` 附近）
- Create: `core/src/test/resources/fixtures/pidof_single.txt`、`pidof_multi.txt`、`pidof_empty.txt`（真机录制）
- Test: `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt`

**Interfaces:**
- Produces: `suspend fun CommandRunner.pidOf(serial: String, pkg: String): List<Int>`——空列表 = 未运行；`AdbCommandException` = 传输层失败。Task 3 的 controller 消费它。
- Produces: `FakeAdbProcessRunner.whenArgsContains` 变为「同关键词规则替换」——Task 3 的重解析测试用它模拟 app 重启后 pidof 输出变化。

- [ ] **Step 1: 从真机录制三个 fixture**

用 Bash 工具（Git Bash）执行（**不要用 PowerShell 重定向**——默认 UTF-16 会写坏文件）。先 `adb devices` 找在线设备 serial；无设备在线则请用户接上设备再录：

```bash
adb devices
SERIAL=<serial>
# 单 pid：挑一个正在运行的三方 app（adb shell dumpsys activity top | head 找包名，或用已知 app）
adb -s $SERIAL shell pidof <运行中的三方包> > core/src/test/resources/fixtures/pidof_single.txt
# 多 pid：多进程 app（如 com.android.systemui，或微信类应用）
adb -s $SERIAL shell pidof <多进程包> > core/src/test/resources/fixtures/pidof_multi.txt
# 空：未安装的包名
adb -s $SERIAL shell pidof com.example.not.installed > core/src/test/resources/fixtures/pidof_empty.txt
```

每个文件头加注释行（`adb -s $SERIAL shell getprop` 取型号/manufacturer/版本/SDK/build id）：

```
# source: <model> / <manufacturer> / Android <release> (SDK <sdk>) / build <id>
# recorded: 2026-10-14 (git bash redirect, adb -s <serial> shell pidof <pkg>)
# note: pty appends \r\n — parser splits on any whitespace
```

- [ ] **Step 2: 写失败测试**

`CommandRunnerTest.kt` 追加（fixture 加载照抄 dumpsys 测试的模式：`dropWhile { it.startsWith("#") }`；fixture 是真实数据，期望值也从 fixture 文本按同一解析语义读出，不在测试里手写数字）：

```kotlin
private fun pidNums(name: String): List<Int> =
    javaClass.classLoader!!.getResource("fixtures/$name")!!
        .readText().lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n")
        .trim().split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }

@Test fun pidOf_parses_single_multi_and_empty() = runTest {
    val runner = FakeAdbProcessRunner()
    fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/$name")!!
        .readText().lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n")
    runner.whenArgsContains(listOf("pidof", "com.one"),   AdbProcessResult(0, fixture("pidof_single.txt"), ""))
    runner.whenArgsContains(listOf("pidof", "com.multi"), AdbProcessResult(0, fixture("pidof_multi.txt"), ""))
    runner.whenArgsContains(listOf("pidof", "com.nope"),  AdbProcessResult(0, fixture("pidof_empty.txt"), ""))
    val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertEquals(pidNums("pidof_single.txt"), cr.pidOf("abc", "com.one"))
    assertEquals(pidNums("pidof_multi.txt"),  cr.pidOf("abc", "com.multi"))
    assertEquals(emptyList<Int>(),             cr.pidOf("abc", "com.nope"))
    // argv 守卫：包名作为 shell 命令的一部分发出
    assertTrue(runner.runs.last().joinToString(" ").contains("shell pidof com.nope"))
}

@Test fun pidOf_rejects_invalid_package_name() = runTest {
    val cr = CommandRunner({ FakeAdbProcessRunner() }, FakeAdbProcessRunner(), NoopLogger, this, CommandRunner.AdbServerStarter{})
    assertFailsWith<IllegalArgumentException> { cr.pidOf("abc", "com.foo; rm -rf /") }
}
```

（`assertFailsWith` 需要 `import kotlin.test.assertFailsWith`。）

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.adb.CommandRunnerTest"`
Expected: COMPILATION ERROR——`pidOf` 不存在（红）。

- [ ] **Step 4: 实现**

`CommandRunner.kt` 的 `listPackages` 之后加：

```kotlin
/** `adb shell pidof <pkg>` → 该应用当前运行进程的 pid 集合。**空输出 = 未运行，返回空列表**，
 *  不是错误——toolbox/toybox 的 pidof 找不到进程时打印空串并退 1，故追加 `|| true` 让设备端
 *  shell 永远退 0（传输层失败仍抛 [AdbCommandException]）。输出按任意空白切分（pty 会把 \n
 *  变 \r\n，`\s+` 兼收）。[pkg] 经 [pkgRegex] 守卫防 shell 注入。 */
suspend fun pidOf(serial: String, pkg: String): List<Int> {
    require(pkgRegex.matches(pkg)) { "invalid package name: $pkg" }
    val out = runShellCmd(serial, "pidof $pkg || true")
    return out.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }
}
```

`FakeAdbProcessRunner.whenArgsContains`（`AdbProcessRunner.kt`）改为同关键词替换：

```kotlin
fun whenArgsContains(keywords: List<String>, result: AdbProcessResult) {
    // Replace an existing rule with IDENTICAL keywords so a test can vary one command's
    // scripted output over time (e.g. logcat package pid re-resolution after app restart).
    // Distinct keyword sets append as before; first-match-wins order unchanged.
    val i = scripts.indexOfFirst { it.keywords == keywords }
    if (i >= 0) scripts[i] = Rule(keywords, result) else scripts.add(Rule(keywords, result))
}
```

- [ ] **Step 5: 全量跑一遍 fake 自身与 CommandRunner 测试（防 whenArgsContains 语义回归）**

Run: `./gradlew :core:test --tests "com.adbgui.core.adb.FakeAdbProcessRunnerTest" --tests "com.adbgui.core.adb.CommandRunnerTest"`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(core): CommandRunner.pidOf with real recorded fixtures"
```

---

### Task 3: `LogcatController` 包名解析循环 + `snapshotTags()`

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatController.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatControllerTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `LogcatFilters`（含 `packageName`/`pids`）、Task 2 的 `CommandRunner.pidOf`。
- Produces: 构造参数 `reResolveIntervalMs: Long = 5000`（测试传小值）；`fun snapshotTags(): List<Pair<String, Int>>`（非挂起，从已发布 `lines` 快照现算，频次降序，cap 200）——Task 5 的 VM 转发、Task 6 的 Tag 下拉消费。语义：`setFilters` 中 `packageName` 与当前相同（非空）时 controller 忽略传入 `pids`、保留自己解析出的集合（UI 改级别/文本不会把已解析 pids 抹掉）。

- [ ] **Step 1: 写失败测试**

`LogcatControllerTest.kt`：`controller(...)` 工厂加参数 `reResolveIntervalMs: Long = 10_000` 并透传给 `LogcatController(...)`。追加测试（**注意 Global Constraints 里的 advanceUntilIdle 陷阱**）：

```kotlin
@Test fun package_filter_resolves_pids_and_filters_lines() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf(
        "08-17 10:23:45.100  100  200 I TagA: from app",
        "08-17 10:23:45.200  999  201 I TagB: other app",
    ))
    runner.whenArgsContains(listOf("pidof", "com.foo"), AdbProcessResult(0, "100\n", ""))
    val c = controller(runner, this)
    c.start("abc"); advanceUntilIdle()
    c.setFilters(LogcatFilters(packageName = "com.foo"))
    runCurrent()   // setFilters 协程 + 解析循环首拍（立即解析一次）
    assertEquals(1, c.lines.value.size)
    assertEquals("from app", c.lines.value[0].message)
    c.setFilters(LogcatFilters()); runCurrent()   // 停循环，否则下面的收尾会挂
    c.stop()
}

@Test fun reResolve_merges_new_pids_keeping_old() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf(
        "08-17 10:23:45.100  100  200 I T: old pid",
        "08-17 10:23:45.200  300  201 I T: new pid",
        "08-17 10:23:45.300  999  202 I T: other",
    ))
    runner.whenArgsContains(listOf("pidof", "com.foo"), AdbProcessResult(0, "100\n", ""))
    val c = controller(runner, this, reResolveIntervalMs = 1000)
    c.start("abc"); advanceUntilIdle()
    c.setFilters(LogcatFilters(packageName = "com.foo"))
    runCurrent()                       // 首拍：pids={100}
    // 模拟 app 重启：pidof 现在返回新 pid（Task 2 的同关键词替换规则）
    runner.whenArgsContains(listOf("pidof", "com.foo"), AdbProcessResult(0, "300\n", ""))
    advanceTimeBy(1001); runCurrent()  // 第二拍
    assertEquals(2, c.lines.value.size)  // 旧 pid 100 保留 + 新 pid 300
    c.setFilters(LogcatFilters()); runCurrent(); c.stop()
}

@Test fun pidof_failure_keeps_old_pids_and_stream_alive() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf(
        "08-17 10:23:45.100  100  200 I T: from app",
        "08-17 10:23:45.200  999  201 I T: other",
    ))
    runner.whenArgsContains(listOf("pidof", "com.foo"), AdbProcessResult(0, "100\n", ""))
    val c = controller(runner, this, reResolveIntervalMs = 1000)
    c.start("abc"); advanceUntilIdle()
    c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
    // 设备闪断：pidof 抛 AdbCommandException（runCmd 非零退码）
    runner.whenArgsContains(listOf("pidof", "com.foo"), AdbProcessResult(1, "", "error: device offline"))
    advanceTimeBy(1001); runCurrent()
    assertEquals(1, c.lines.value.size)          // 旧 pids 保留
    assertEquals(LogcatStatus.RUNNING, c.status.value)  // 流不死
    c.setFilters(LogcatFilters()); runCurrent(); c.stop()
}

@Test fun clearing_package_stops_resolve_loop() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: m"))
    runner.whenArgsContains(listOf("pidof", "com.foo"), AdbProcessResult(0, "100\n", ""))
    val c = controller(runner, this, reResolveIntervalMs = 1000)
    c.start("abc"); advanceUntilIdle()
    c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
    val pidofCallsBefore = runner.runs.count { args -> args.any { it.contains("pidof") } }
    c.setFilters(LogcatFilters(packageName = null)); runCurrent()   // 清包 → 停循环
    advanceTimeBy(10_001)  // 若循环还活着这里会 tick 10 次
    assertEquals(pidofCallsBefore, runner.runs.count { args -> args.any { it.contains("pidof") } })
    c.stop()
}

@Test fun device_switch_restarts_resolve_for_new_serial() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: m"))
    runner.whenArgsContains(listOf("pidof", "com.foo"), AdbProcessResult(0, "100\n", ""))
    val c = controller(runner, this)
    c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
    c.start("def"); runCurrent()   // 换设备 → stop() + 循环重启，用新 serial 解析
    assertTrue(runner.runs.any { args -> args.joinToString(" ").contains("-s def shell pidof com.foo") })
    c.setFilters(LogcatFilters()); runCurrent(); c.stop()
}

@Test fun setFilters_with_same_package_keeps_resolved_pids() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: m"))
    runner.whenArgsContains(listOf("pidof", "com.foo"), AdbProcessResult(0, "100\n", ""))
    val c = controller(runner, this)
    c.start("abc"); advanceUntilIdle()
    c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
    // 用户改了级别，但传了空 pids —— controller 必须保住已解析的 {100}
    c.setFilters(LogcatFilters(packageName = "com.foo", levelSet = setOf(com.adbgui.core.domain.LogcatLevel.I)))
    runCurrent()
    assertEquals(setOf(100), c.filters.value.pids)
    c.setFilters(LogcatFilters()); runCurrent(); c.stop()
}

@Test fun snapshotTags_counts_from_published_lines_desc_capped() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines((1..3).map { "08-17 10:23:45.10$it  100  200 I Tag1: m$it" } +
        listOf("08-17 10:23:45.400  100  201 I Tag2: m"))
    val c = controller(runner, this)
    c.start("abc"); advanceUntilIdle()
    assertEquals(listOf("Tag1" to 3, "Tag2" to 1), c.snapshotTags())
    c.stop()
}

@Test fun dumpLogcat_applies_pids_filter() = runTest {
    // spec 测试计划 #3：全量导出与实时视图共用 matches() 谓词——pids 过滤必须同样生效。
    val runner = FakeAdbProcessRunner()
    runner.setStreamLinesOnce(listOf(
        "08-17 10:23:45.100  100  200 I Tag: from app",
        "08-17 10:23:45.200  999  201 I Tag: other",
    ))
    val c = controller(runner, this)
    val got = mutableListOf<String>()
    c.dumpLogcat("abc", LogcatFilters(pids = setOf(100))) { got += it }
    assertEquals(1, got.size)
    assertTrue(got[0].contains("from app"))
}
```

（`snapshotTags` 的 cap 200 是 `take(200)` 实现细节，不单测——构造 250 个 distinct tag 的输入不值得；以实现里注释标注。）

（需要 `import kotlinx.coroutines.test.advanceTimeBy`、`import kotlinx.coroutines.test.runCurrent`——文件里已有。）

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatControllerTest"`
Expected: COMPILATION ERROR（`reResolveIntervalMs` 参数不存在）/ 断言失败（pids 不更新）。红。

- [ ] **Step 3: 实现**

`LogcatController` 构造参数追加（`publishIntervalMs` 之后）：

```kotlin
/** 包过滤激活期间 pidof 重解析间隔（spec D2）。测试传小值。 */
private val reResolveIntervalMs: Long = 5000,
```

字段（现有 `@Volatile stream` 附近）：

```kotlin
@Volatile private var currentSerial: String? = null
private var resolveJob: Job? = null
@Volatile private var resolveLoopPkg: String? = null
```

`start()`/`stop()` 改为：

```kotlin
fun start(serial: String) {
    stop()
    currentSerial = serial
    // 设备切换 → pid 空间不同，重启解析循环（若包过滤激活）
    if (resolveLoopPkg != null) restartResolveLoop(resolveLoopPkg)
    job = scope.launch {
        launch { publishLoop() }
        mutex.withLock {
            ring.clear(); filtered.clear()
            linesDirty = true
            _error.value = null
        }
        flushSignal.trySend(Unit)
        runLoop(serial)
    }
}

fun stop() {
    job?.cancel(); job = null
    resolveJob?.cancel(); resolveJob = null   // resolveLoopPkg 故意保留：下次 start() 重启
    stream?.kill(); stream = null
    currentSerial = null
    linesDirty = false
    _status.value = LogcatStatus.IDLE
}
```

`setFilters` 改为：

```kotlin
fun setFilters(f: LogcatFilters) {
    scope.launch {
        mutex.withLock {
            // 包过滤激活期间，pids 由解析循环拥有——UI 改其他条件（传空 pids）不得抹掉已解析集合
            val effective = if (f.packageName != null && f.packageName == _filters.value.packageName)
                f.copy(pids = _filters.value.pids) else f
            _filters.value = effective
            recomputeFiltered(); linesDirty = true
        }
        publishOnce()
    }
    if (f.packageName != resolveLoopPkg) restartResolveLoop(f.packageName)
}
```

新增（`recomputeFiltered` 之后）：

```kotlin
private fun restartResolveLoop(pkg: String?) {
    resolveJob?.cancel(); resolveJob = null
    resolveLoopPkg = pkg
    if (pkg != null) resolveJob = scope.launch { resolveLoop(pkg) }
}

/** spec D2：首拍立即解析，之后每 [reResolveIntervalMs] 重解析。pid **只增不减**（app 重启换
 *  pid 后旧 pid 的历史行仍显示）；解析失败保旧值不打断流；换包/清包由 [setFilters] 重启循环。 */
private suspend fun resolveLoop(pkg: String) {
    while (true) {
        val serial = currentSerial
        if (serial != null) {
            val resolved = try {
                commands.pidOf(serial, pkg)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logger.warn("pidof failed for $pkg on $serial: ${t.message}")  // 保旧 pids
                null
            }
            if (resolved != null) mergePids(pkg, resolved)
        }
        delay(reResolveIntervalMs)
    }
}

private suspend fun mergePids(pkg: String, newPids: List<Int>) {
    mutex.withLock {
        val f = _filters.value
        if (f.packageName != pkg) return   // 解析期间用户已换包/清包，结果作废
        val merged = f.pids + newPids      // 空列表 → 无变化（app 未运行不清旧 pids）
        if (merged == f.pids) return
        _filters.value = f.copy(pids = merged)
        recomputeFiltered(); linesDirty = true
    }
    publishOnce()
}

/** 当前已发布（已过滤）视图里的 distinct tag→count，频次降序，cap 200。打开 Tag 下拉时调；
 *  非挂起——读 immutable List 快照无需持锁，且 tag 列表天然跟随当前过滤视图。 */
fun snapshotTags(): List<Pair<String, Int>> {
    val counts = mutableMapOf<String, Int>()
    for (l in _lines.value) counts[l.tag] = (counts[l.tag] ?: 0) + 1
    return counts.entries.sortedByDescending { it.value }.take(200).map { it.key to it.value }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatControllerTest"`
Expected: PASS（含既有全部用例——`dumpLogcat_applies_filters...` 等不受影响）。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(core): logcat package pid resolve loop + snapshotTags"
```

---

### Task 4: `SavedLogcatFilters` 持久化字段

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `@Serializable data class SavedLogcatFilters(levelSet: List<String> = ["V","D","I","W","E","F"], tagInclude: String? = null, tagExcludes: List<String> = emptyList(), text: String? = null, textIsRegex: Boolean = false, packageName: String? = null)` 与 `Settings.logcatFilters: SavedLogcatFilters = SavedLogcatFilters()`——Task 5 的 VM 消费。

- [ ] **Step 1: 写失败测试**

`SettingsStoreTest.kt` 追加：

```kotlin
@Test fun logcat_filters_roundtrip() = runTest {
    val dir = tmpDir()
    val store = SettingsStore(dir)
    val saved = com.adbgui.core.settings.SavedLogcatFilters(
        levelSet = listOf("I", "W"), tagInclude = "Tag", tagExcludes = listOf("noise", "chatty"),
        text = "hello", textIsRegex = true, packageName = "com.foo",
    )
    store.save(Settings(logcatFilters = saved))
    assertEquals(saved, SettingsStore(dir).load().logcatFilters)
}

@Test fun settings_json_without_logcat_filters_loads_default() = runTest {
    val dir = tmpDir()
    // 旧版本 settings.json 没有该字段 → 默认值（向后兼容）
    Files.writeString(dir.resolve("settings.json"), """{"adbPathOverride":"/opt/adb"}""")
    assertEquals(com.adbgui.core.settings.SavedLogcatFilters(),
        SettingsStore(dir).load().logcatFilters)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.settings.SettingsStoreTest"`
Expected: COMPILATION ERROR（`SavedLogcatFilters` 不存在）。

- [ ] **Step 3: 实现**

`SettingsStore.kt`：`Settings` data class 之前加：

```kotlin
/** Logcat 页过滤条件持久化（spec 2026-10-14 D4）。pids 是会话态**不存**——恢复时 controller
 *  重新 pidof 解析。levelSet 存 LogcatLevel 名称，未知名称在恢复侧被忽略。 */
@Serializable
data class SavedLogcatFilters(
    val levelSet: List<String> = listOf("V", "D", "I", "W", "E", "F"),
    val tagInclude: String? = null,
    val tagExcludes: List<String> = emptyList(),
    val text: String? = null,
    val textIsRegex: Boolean = false,
    val packageName: String? = null,
)
```

`Settings` 里 `logcatRecordingMaxTotalMb` 之后加一行：

```kotlin
/** Logcat 过滤条件（上一次会话的），spec 2026-10-14 D4。 */
val logcatFilters: SavedLogcatFilters = SavedLogcatFilters(),
```

- [ ] **Step 4: 运行测试确认通过 + 提交**

Run: `./gradlew :core:test --tests "com.adbgui.core.settings.SettingsStoreTest"`
Expected: PASS。

```bash
git add -A && git commit -m "feat(core): persist logcat filters in settings.json"
```

---

### Task 5: `LogcatViewModel`——包列表 / snapshotTags 转发 / initialFilters / 防抖持久化

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatViewModel.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt:97`（接线）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/LogcatViewModelTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `controller.snapshotTags()`/`setFilters`、Task 4 的 `SettingsStore`/`SavedLogcatFilters`、现有 `DeviceRepository.listPackages(serial): List<PackageInfo>`（`PackageInfo(name: String, isSystem: Boolean)`）。
- Produces（Task 6 的 UI 消费）：构造参数变更为 `LogcatViewModel(controller, recording, repository: DeviceRepository, store: SettingsStore, selectedSerial, deviceOnline, scope, logger: Logger = NoopLogger)`；`val packages: StateFlow<List<PackageInfo>?>`、`val packagesError: StateFlow<String?>`、`fun loadPackages()`、`fun snapshotTags(): List<Pair<String, Int>>`、`val initialFilters: LogcatFilters`（从 store 恢复一次）。

- [ ] **Step 1: 写失败测试**

`LogcatViewModelTest.kt`：加两个 helper（照 `AppConsoleViewModelTest` 的模式）：

```kotlin
private fun repo(runner: FakeAdbProcessRunner, scope: CoroutineScope): com.adbgui.core.device.DeviceRepository {
    val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
    val tracker = FakeTracker()
    val history = com.adbgui.core.device.DeviceHistoryStore(Files.createTempDirectory("adbgui-hist"), clock = { 0L }, io = Dispatchers.Unconfined)
    return com.adbgui.core.device.DeviceRepository(tracker, history, cmd, NoopLogger, scope)
}

private fun store(): SettingsStore {
    val dir = Files.createTempDirectory("adbgui-settings")
    return SettingsStore(dir, io = Dispatchers.Unconfined)
}
```

既有测试里的每个 `LogcatViewModel(controller(...), recordingManager(...), selected, ..., scope)` 直接构造点，在 `recordingManager` 之后**插入两个实参** `repo(runner, backgroundScope), store()`（注意 VM 的 scope 实参维持各测试原样——有的传 `this`、有的传 `backgroundScope`，防抖任务要在测试调度器上可见就必须传 `this`）。追加新测试：

```kotlin
@Test fun loadPackages_forwards_repository_and_caches() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.whenArgsContains(listOf("pm", "list", "-f"),
        AdbProcessResult(0, "package:/data/app/com.foo-1/base.apk=com.foo\n", ""))
    runner.whenArgsContains(listOf("packages", "-s"), AdbProcessResult(0, "", ""))
    val selected = MutableStateFlow<String?>("abc")
    val testVm = LogcatViewModel(controller(runner, this), recordingManager(runner, backgroundScope),
        repo(runner, backgroundScope), store(), selected, MutableStateFlow(true), this)
    testVm.loadPackages(); advanceUntilIdle()
    assertEquals(1, testVm.packages.value?.size)
    assertEquals("com.foo", testVm.packages.value!![0].name)
    val runsBefore = runner.runs.size
    testVm.loadPackages()   // 已缓存，不再发 adb
    advanceUntilIdle()
    assertEquals(runsBefore, runner.runs.size)
    testVm.stop()
}

@Test fun loadPackages_failure_sets_inline_error_and_retries_on_next_call() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setDefault(AdbProcessResult(1, "", "boom"))
    val selected = MutableStateFlow<String?>("abc")
    val testVm = LogcatViewModel(controller(runner, this), recordingManager(runner, backgroundScope),
        repo(runner, backgroundScope), store(), selected, MutableStateFlow(true), this)
    testVm.loadPackages(); advanceUntilIdle()
    assertNotNull(testVm.packagesError.value)
    assertNull(testVm.packages.value)
    runner.whenArgsContains(listOf("pm", "list", "-f"),
        AdbProcessResult(0, "package:/data/app/com.foo-1/base.apk=com.foo\n", ""))
    runner.whenArgsContains(listOf("packages", "-s"), AdbProcessResult(0, "", ""))
    testVm.loadPackages(); advanceUntilIdle()   // 失败后允许重试
    assertEquals("com.foo", testVm.packages.value?.get(0)?.name)
    testVm.stop()
}

@Test fun setFilters_persists_after_500ms_debounce() = runTest {
    val runner = FakeAdbProcessRunner()
    val st = store()
    val testVm = LogcatViewModel(controller(runner, this), recordingManager(runner, backgroundScope),
        repo(runner, backgroundScope), st, MutableStateFlow("abc"), MutableStateFlow(true), this)
    testVm.setFilters(com.adbgui.core.device.LogcatFilters(packageName = "com.foo", tagInclude = "T"))
    assertEquals(com.adbgui.core.settings.SavedLogcatFilters(), st.state.value.logcatFilters)  // 未到防抖窗口
    advanceTimeBy(600)
    assertEquals("com.foo", st.state.value.logcatFilters.packageName)
    assertEquals("T", st.state.value.logcatFilters.tagInclude)
    testVm.stop()
}

@Test fun initialFilters_restored_from_settings() = runTest {
    val st = store()
    st.save(com.adbgui.core.settings.Settings(
        logcatFilters = com.adbgui.core.settings.SavedLogcatFilters(
            levelSet = listOf("I"), packageName = "com.foo", tagExcludes = listOf("noise"))))
    val runner = FakeAdbProcessRunner()
    val testVm = LogcatViewModel(controller(runner, this), recordingManager(runner, backgroundScope),
        repo(runner, backgroundScope), st, MutableStateFlow("abc"), MutableStateFlow(true), this)
    assertEquals("com.foo", testVm.initialFilters.packageName)
    assertEquals(setOf(com.adbgui.core.domain.LogcatLevel.I), testVm.initialFilters.levelSet)
    assertEquals(listOf("noise"), testVm.initialFilters.tagExcludes)
    testVm.stop()
}
```

（`advanceTimeBy` 需 `import kotlinx.coroutines.test.advanceTimeBy`。）

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.LogcatViewModelTest"`
Expected: COMPILATION ERROR（构造参数不存在 / 属性不存在）。

- [ ] **Step 3: 实现**

`LogcatViewModel.kt`：

```kotlin
class LogcatViewModel(
    private val controller: LogcatController,
    private val recording: com.adbgui.core.device.LogcatRecordingManager,
    private val repository: com.adbgui.core.device.DeviceRepository,
    private val store: com.adbgui.core.settings.SettingsStore,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    val deviceOnline: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val logger: com.adbgui.core.log.Logger = com.adbgui.core.log.NoopLogger,
) {
```

新增成员（`filters` 转发附近）：

```kotlin
/** 上次会话保存的过滤条件（恢复 UI 初始值用，spec D4）。pids 不持久化——由 controller 恢复时重新解析。 */
val initialFilters: LogcatFilters = store.state.value.logcatFilters.toFilters()

private fun com.adbgui.core.settings.SavedLogcatFilters.toFilters() = LogcatFilters(
    levelSet = levelSet.mapNotNull { name ->
        com.adbgui.core.domain.LogcatLevel.entries.firstOrNull { it.name == name }
    }.toSet().ifEmpty { com.adbgui.core.domain.LogcatLevel.entries.toSet() },
    tagInclude = tagInclude,
    tagExcludes = tagExcludes,
    text = text,
    textIsRegex = textIsRegex,
    packageName = packageName,
)

// --- App 下拉数据（惰性加载一次并缓存；失败内联提示，下次打开下拉重试） ---
private val _packages = MutableStateFlow<List<com.adbgui.core.domain.PackageInfo>?>(null)
val packages: StateFlow<List<com.adbgui.core.domain.PackageInfo>?> = _packages.asStateFlow()
private val _packagesError = MutableStateFlow<String?>(null)
val packagesError: StateFlow<String?> = _packagesError.asStateFlow()

fun loadPackages() {
    if (_packages.value != null) return
    val serial = selectedSerial.value ?: return
    scope.launch {
        runCatching { repository.listPackages(serial) }
            .onSuccess { _packages.value = it; _packagesError.value = null }
            .onFailure { _packagesError.value = it.message ?: it.javaClass.simpleName }
    }
}

/** Tag 下拉数据源（打开下拉时取一次）。 */
fun snapshotTags(): List<Pair<String, Int>> = controller.snapshotTags()

// --- 过滤持久化（spec D4：500ms 防抖；失败静默降级 log warn） ---
private var persistJob: Job? = null

fun setFilters(f: LogcatFilters) {
    controller.setFilters(f)
    persistJob?.cancel()
    persistJob = scope.launch {
        delay(500)
        runCatching { store.update { it.copy(logcatFilters = f.toSaved()) } }
            .onFailure { logger.warn("logcat filters persist failed: ${it.message}") }
    }
}

private fun LogcatFilters.toSaved() = com.adbgui.core.settings.SavedLogcatFilters(
    levelSet = levelSet.map { it.name },
    tagInclude = tagInclude,
    tagExcludes = tagExcludes,
    text = text,
    textIsRegex = textIsRegex,
    packageName = packageName,
)
```

删除旧的 `fun setFilters(f: LogcatFilters) = controller.setFilters(f)`（被上面的取代）。`import kotlinx.coroutines.delay` 若缺则加。

`Main.kt:97` 接线改为：

```kotlin
val logcatVm = remember { LogcatViewModel(logcatController, logcatRecording, root.repository, root.settings, selectedSerial, deviceOnline, root.scope, root.logger) }
```

- [ ] **Step 4: 运行测试确认通过 + 提交**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.LogcatViewModelTest"`
Expected: PASS（全部，含既有）。

```bash
git add -A && git commit -m "feat(desktop): logcat VM package list, tag snapshot, filter persistence"
```

---

### Task 6: `LogcatFilterBar.kt` UI（App/Tag 下拉 + 右键菜单 + i18n + 接线）

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatFilterBar.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt`（过滤栏移入 FilterBar、行右键、软提示）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`（zh/en 各加 15 键）
- Test: 无 Compose UI 单测（项目约定 VM 单测为主）；验收 = 编译 + Task 7 真机验证

**Interfaces:**
- Consumes: Task 5 的 `vm.packages/packagesError/loadPackages/snapshotTags/initialFilters/setFilters`、Task 1 的 `LogcatFilters` 全字段、现有 `SearchField`（`ui/SearchField.kt`）、`TooltipIconButton`。
- Produces: `@Composable fun LogcatFilterBar(vm, levelSet, text, useRegex, packageName, tagInclude, tagExcludes, pidOnly, onFilters, searchFocus)`——`onFilters: (LogcatFilters) -> Unit` 由 Screen 实现（更新本地 state + `vm.setFilters`）。右键菜单位于 `LogcatScreen` 的行渲染内（需要行级 `line: LogcatLine`）。

- [ ] **Step 1: i18n 键**

`Strings.kt` 的 `zh` map（`"text_search" to "文本搜索",` 附近）加：

```kotlin
"app_filter" to "应用",
"app_filter_all" to "全部应用",
"search_package" to "搜索包名",
"show_system_apps" to "显示系统应用",
"packages_load_failed" to "包列表加载失败，重新打开下拉可重试",
"app_not_running_hint" to "应用未运行或暂无日志（选中后持续检测运行状态）",
"tag_filter" to "Tag",
"tag_filter_all" to "全部",
"exclude" to "排除",
"clear_excludes" to "清除排除",
"filter_by_tag" to "只看此 tag",
"exclude_this_tag" to "排除此 tag",
"filter_by_pid" to "只看此 PID",
"clear_pid_filter" to "清除 PID/应用过滤",
"no_tag_matches" to "当前缓冲无匹配 tag",
```

`en` map（对应位置）加：

```kotlin
"app_filter" to "App",
"app_filter_all" to "All apps",
"search_package" to "Search packages",
"show_system_apps" to "Show system apps",
"packages_load_failed" to "Failed to load packages; reopen the dropdown to retry",
"app_not_running_hint" to "App not running or no logs (running state is re-checked periodically)",
"tag_filter" to "Tag",
"tag_filter_all" to "All",
"exclude" to "Exclude",
"clear_excludes" to "Clear excludes",
"filter_by_tag" to "Only this tag",
"exclude_this_tag" to "Exclude this tag",
"filter_by_pid" to "Only this PID",
"clear_pid_filter" to "Clear PID/app filter",
"no_tag_matches" to "No matching tags in buffer",
```

- [ ] **Step 2: 新建 `LogcatFilterBar.kt`**

```kotlin
package com.adbgui.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adbgui.core.device.LogcatFilters
import com.adbgui.core.domain.LogcatLevel
import com.adbgui.desktop.ui.i18n.Strings

/** AS 式过滤栏（spec 2026-10-14 §3.6）：级别 / App / Tag 三个下拉 + 全文搜索（含正则 pill）。
 *  无内部过滤状态——全部 state 由 [LogcatScreen] 提升，经参数传入；任何变更构造完整
 *  [LogcatFilters] 回调 [onFilters]（Screen 更新本地 state 并转发 vm.setFilters）。
 *  过滤栏右侧的控制按钮（暂停/录制/清屏/复制/导出）留在 LogcatScreen——本组件只管过滤。 */
@Composable
fun LogcatFilterBar(
    vm: LogcatViewModel,
    levelSet: Set<LogcatLevel>,
    text: String,
    useRegex: Boolean,
    packageName: String?,
    tagInclude: String?,
    tagExcludes: List<String>,
    pidOnly: Int?,
    onFilters: (LogcatFilters) -> Unit,
    searchFocus: FocusRequester,
) {
    fun update(
        pkg: String? = packageName, tag: String? = tagInclude, excl: List<String> = tagExcludes,
        pid: Int? = pidOnly, lvl: Set<LogcatLevel> = levelSet, txt: String = text, regex: Boolean = useRegex,
    ) = onFilters(LogcatFilters(
        levelSet = lvl, packageName = pkg, pids = pid?.let { setOf(it) } ?: emptySet(),
        tagInclude = tag, tagExcludes = excl, text = txt.ifBlank { null }, textIsRegex = regex,
    ))

    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- 级别（多选，逻辑与旧 LogcatScreen 相同，迁入） ----
        var levelMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { levelMenuOpen = true }) {
                Text("${Strings.t("level")}: ${levelLabel(levelSet)}")
            }
            DropdownMenu(expanded = levelMenuOpen, onDismissRequest = { levelMenuOpen = false }) {
                LogcatLevel.entries.forEach { lvl ->
                    DropdownMenuItem(onClick = {
                        val ns = if (lvl in levelSet) levelSet - lvl else levelSet + lvl
                        levelMenuOpen = false
                        update(lvl = ns)
                    }) { Text("${if (lvl in levelSet) "✓" else "•"}  ${lvl.name}") }
                }
            }
        }

        // ---- App（包名 → pidof，spec D1/D2） ----
        var appMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { appMenuOpen = true; vm.loadPackages() }) {
                Text(
                    packageName ?: Strings.t("app_filter_all"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
            AppFilterDropdown(
                expanded = appMenuOpen,
                onDismiss = { appMenuOpen = false },
                vm = vm,
                packageName = packageName,
                onSelect = { appMenuOpen = false; update(pkg = it, pid = null) },
            )
        }

        // ---- Tag（来自当前缓冲，频次降序；include 单选 + 每行排除 + 清除排除） ----
        var tagMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { tagMenuOpen = true }) {
                Text(
                    tagInclude ?: Strings.t("tag_filter"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
            TagFilterDropdown(
                expanded = tagMenuOpen,
                onDismiss = { tagMenuOpen = false },
                tags = remember(tagMenuOpen) { if (tagMenuOpen) vm.snapshotTags() else emptyList() },
                tagInclude = tagInclude,
                tagExcludes = tagExcludes,
                onSelect = { tagMenuOpen = false; update(tag = it) },
                onExclude = { update(excl = tagExcludes + it) },
                onClearInclude = { tagMenuOpen = false; update(tag = null) },
                onClearExcludes = { update(excl = emptyList()) },
            )
        }

        // ---- 全文搜索（迁移自旧 LogcatScreen，含正则 pill） ----
        com.adbgui.desktop.ui.SearchField(
            value = text,
            onValueChange = { update(txt = it) },
            placeholder = Strings.t("text_search"),
            regex = useRegex,
            onRegexChange = { update(regex = it) },
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
    }
}

@Composable
private fun AppFilterDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    vm: LogcatViewModel,
    packageName: String?,
    onSelect: (String?) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = 320.dp)) {
        var query by remember { mutableStateOf("") }
        var showSystem by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(Strings.t("search_package")) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
            Text(Strings.t("show_system_apps"), style = MaterialTheme.typography.caption)
        }
        vm.packagesError.value?.let {
            Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        val pkgs = (vm.packages.value ?: emptyList())
            .filter { (showSystem || !it.isSystem) && it.name.contains(query, ignoreCase = true) }
        DropdownMenuItem(onClick = { onDismiss(); onSelect(null) }) {
            Text((if (packageName == null) "✓ " else "") + Strings.t("app_filter_all"))
        }
        if (pkgs.isEmpty()) {
            Text(Strings.t("no_tag_matches"), style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(8.dp))
        }
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(pkgs) { p ->
                DropdownMenuItem(onClick = { onSelect(p.name) }) {
                    Text((if (p.name == packageName) "✓ " else "") + p.name, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TagFilterDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    tags: List<Pair<String, Int>>,
    tagInclude: String?,
    tagExcludes: List<String>,
    onSelect: (String?) -> Unit,
    onExclude: (String) -> Unit,
    onClearInclude: () -> Unit,
    onClearExcludes: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = 280.dp)) {
        var query by remember { mutableStateOf("") }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(Strings.t("text_search")) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        DropdownMenuItem(onClick = onClearInclude) {
            Text((if (tagInclude == null) "✓ " else "") + Strings.t("tag_filter_all"))
        }
        if (tagExcludes.isNotEmpty()) {
            DropdownMenuItem(onClick = onClearExcludes) {
                Text("${Strings.t("clear_excludes")} (${tagExcludes.size})", style = MaterialTheme.typography.caption)
            }
        }
        val shown = tags.filter { it.first.contains(query, ignoreCase = true) }
        if (shown.isEmpty()) {
            Text(Strings.t("no_tag_matches"), style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(8.dp))
        }
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(shown) { (tag, count) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(tag) }) {
                    Text(
                        (if (tag == tagInclude) "✓ " else "") + "$tag ($count)",
                        maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    TextButton(onClick = { onExclude(tag) }) { Text(Strings.t("exclude"), style = MaterialTheme.typography.caption) }
                }
            }
        }
    }
}

private fun levelLabel(levelSet: Set<LogcatLevel>): String =
    if (levelSet.size == LogcatLevel.entries.size) Strings.t("level_all")
    else levelSet.joinToString(",") { it.name }
```

（`levelLabel` 从 `LogcatScreen.kt` **移**过来——那边删掉私有副本。）

- [ ] **Step 3: 改 `LogcatScreen.kt`**

a) 本地 state 初始化改为从 `vm.initialFilters` 起（`LogcatScreen` 顶部现有 `var levelSet/text/useRegex` 处）：

```kotlin
val init = vm.initialFilters
var levelSet by remember { mutableStateOf(init.levelSet) }
var text by remember { mutableStateOf(init.text ?: "") }
var useRegex by remember { mutableStateOf(init.textIsRegex) }
var packageName by remember { mutableStateOf(init.packageName) }
var tagInclude by remember { mutableStateOf(init.tagInclude) }
var tagExcludes by remember { mutableStateOf(init.tagExcludes) }
var pidOnly by remember { mutableStateOf(init.pids.singleOrNull()) }
```

b) 过滤栏 Row 里删掉「级别下拉 Box」「SearchField」两个块和 `levelMenuOpen` state，替换为：

```kotlin
Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    LogcatFilterBar(
        vm = vm,
        levelSet = levelSet, text = text, useRegex = useRegex,
        packageName = packageName, tagInclude = tagInclude, tagExcludes = tagExcludes, pidOnly = pidOnly,
        onFilters = { f ->
            levelSet = f.levelSet; text = f.text ?: ""; useRegex = f.textIsRegex
            packageName = f.packageName; tagInclude = f.tagInclude; tagExcludes = f.tagExcludes
            pidOnly = f.pids.singleOrNull()
            vm.setFilters(f)
        },
        searchFocus = searchFocus,
    )
    // ……后面的 ⏸ / ⏺ / 📁 / 🗑 / 📋 / ⬇ 按钮块原样保留在同一 Row 里……
```

c) 行渲染加右键（`itemsIndexed(lines)` 内，`Text(...)` 替换为）：

```kotlin
itemsIndexed(lines) { _, line ->
    var lineMenu by remember { mutableStateOf(false) }
    Box(Modifier.mouseClickable { if (buttons.isSecondaryPressed) lineMenu = true }) {
        Text(
            highlightMatches(line.raw, query, matchHighlight),
            color = levelColor(line.level),
            style = MaterialTheme.typography.body2,
        )
        DropdownMenu(expanded = lineMenu, onDismissRequest = { lineMenu = false }) {
            DropdownMenuItem(onClick = { lineMenu = false; applyLineFilters { f -> f.copy(tagInclude = line.tag) } }) {
                Text(Strings.t("filter_by_tag"))
            }
            DropdownMenuItem(onClick = { lineMenu = false; applyLineFilters { f -> f.copy(tagExcludes = (f.tagExcludes + line.tag).distinct()) } }) {
                Text(Strings.t("exclude_this_tag"))
            }
            DropdownMenuItem(onClick = { lineMenu = false; applyLineFilters { f -> f.copy(packageName = null, pids = setOf(line.pid)) } }) {
                Text(Strings.t("filter_by_pid"))
            }
            if (packageName != null || pidOnly != null) {
                DropdownMenuItem(onClick = { lineMenu = false; applyLineFilters { f -> f.copy(packageName = null, pids = emptySet()) } }) {
                    Text(Strings.t("clear_pid_filter"))
                }
            }
        }
    }
}
```

`applyLineFilters` 是 Screen 内的本地函数（`onFilters` 逻辑的复用，放在 `onFilters` lambda 定义旁）：

```kotlin
fun applyLineFilters(transform: (LogcatFilters) -> LogcatFilters) {
    val f = transform(LogcatFilters(
        levelSet = levelSet, packageName = packageName,
        pids = pidOnly?.let { setOf(it) } ?: emptySet(),
        tagInclude = tagInclude, tagExcludes = tagExcludes,
        text = text.ifBlank { null }, textIsRegex = useRegex,
    ))
    levelSet = f.levelSet; text = f.text ?: ""; useRegex = f.textIsRegex
    packageName = f.packageName; tagInclude = f.tagInclude; tagExcludes = f.tagExcludes
    pidOnly = f.pids.singleOrNull()
    vm.setFilters(f)
}
```

（`onFilters` lambda 体直接改为调 `applyLineFilters { it }`，避免两份重复。）

d) 「应用未运行」软提示——`Divider()` 之前、auto_recording 文案之后加：

```kotlin
val filtersNow by vm.filters.collectAsState()
if (packageName != null && lines.isEmpty() && status == LogcatStatus.RUNNING && filtersNow.pids.isEmpty()) {
    Text(Strings.t("app_not_running_hint"), Modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
}
```

e) import 增删：加 `androidx.compose.foundation.mouseClickable`、`com.adbgui.core.device.LogcatFilters`（已有）；删 Screen 里不再用的级别下拉相关 import（若编译器提示）。

**右键与 SelectionContainer 冲突的实测点**：`mouseClickable` 挂在行 `Box` 上、`Text` 仍在 `SelectionContainer` 内。若实测右键无响应（Selection 吞事件），降级方案：把 `mouseClickable` 从 `Box` 移到包住 `Text` 的**内层** Box，或改 `Modifier.onPreviewEvent`（`androidx.compose.ui.input.pointer.onPreviewEvent`，判 `it.mouse?.buttons?.isSecondaryPressed == true && it.type == Press`）。以实测为准，改后真机（Task 7）复验。

- [ ] **Step 4: 编译 + 全量测试 + 提交**

Run: `./gradlew :desktop:test :core:test`
Expected: PASS。

```bash
git add -A && git commit -m "feat(desktop): AS-style logcat filter bar - app/tag dropdowns, right-click menu, persisted initial state"
```

---

### Task 7: CHANGELOG + 启动应用 + 真机验证（用户参与）

**Files:**
- Modify: `CHANGELOG.md`（顶部新 `## Unreleased` 或并入现有未发布节——看文件当前结构）

**Interfaces:** 无代码接口；本任务收尾验收。

- [ ] **Step 1: CHANGELOG 条目**

在 CHANGELOG 顶部未发布节加（对照文件内既有条目风格微调措辞）：

```markdown
### Logcat 过滤增强（AS 式）
- 包名/应用过滤：下拉搜索选择（三方/系统可切），`pidof` 解析进程并每 5s 重解析（app 重启后旧进程日志保留）
- Tag 下拉：从当前缓冲按频次列出，单选过滤 + 逐项排除（可多排除）
- 日志行右键：只看/排除此 tag、只看此 PID、清除 PID/应用过滤
- 过滤条件持久化：重启恢复上次条件（pids 不存，恢复时重新解析）
- 全量导出（logcat -d）同样应用包名/tag/pid 过滤
```

- [ ] **Step 2: 启动应用验证（含杀旧 JVM）**

按项目惯例：先杀掉残留的 adbgui 主进程（TaskStop 会留 JVM），再 `./gradlew :desktop:run`。检查项：
1. 过滤栏四控件布局不挤（窄窗口下 App 按钮省略号正常）。
2. App 下拉：搜索、系统开关、选择后日志立即按 app 过滤；选未运行的 app → 软提示出现。
3. Tag 下拉：列表按频次；排除叠加生效。
4. 右键行：四个菜单项各自生效；右键不影响左键选择复制。
5. 暂停/录制/清屏/复制/导出按钮仍工作；Ctrl+F 仍聚焦搜索框。

- [ ] **Step 3: 真机验证（请用户配合，TCL Android 6 优先）**

用户操作清单：
1. TCL TV：选一个运行中的 app → 只见该 app 日志；`am force-stop` 该 app → 软提示出现；重新启动 app（`am start`）→ 新日志出现且**旧日志仍在**（pid 并集）。
2. 断开设备再连：过滤条件仍在（持久化），包过滤自动重新解析。
3. 重启 ADB GUI：过滤条件恢复。

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "docs: changelog for logcat advanced filters"
```

---

## 完成后

1. 全量 `./gradlew :core:test :desktop:test` 绿。
2. CHANGELOG 已记。
3. 用 superpowers:finishing-a-development-branch 收尾（合并策略由用户定：recording-config → filters 依次入 master）。
