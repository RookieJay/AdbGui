# 页面驱动加载 + logcat 增量发布 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让应用启动后不再自作主张地开后台连接/查询，并把 `LogcatController` 每行 O(N) 的全量发布改成节流批量发布——消除"启动空闲 30 秒涨到 1.37GB"的根因。

**Architecture:** 四项互相独立：A) 6 个 ViewModel 的长生命周期 collector 从 init 块挪到页面级 `onPageEntered()`（`AppShell` 的 `when(page)` 保证一个 Screen 的存亡等于页面可见性）；B) `LogcatController` 用 conflated channel + 固定间隔节流发布 `_lines`，把每行 O(N) 降为每行 O(1)；C) `CdpController._net` 补上从未有过的环形上限；D) 打包加 `-Xms64m -Xmx512m` 兜底。

**Tech Stack:** Kotlin 2.1.20，Compose Multiplatform 1.7.3，kotlinx-coroutines 1.9.0，JDK 21。测试：`kotlin.test` + `kotlinx-coroutines-test`（`runTest` / `advanceUntilIdle` / `runCurrent` / `advanceTimeBy`）。

**Spec:** `docs/superpowers/specs/2026-09-16-page-driven-loading-and-logcat-publish-design.md`（本计划的每一步都从该 spec 推导；执行者两份都要读）

## Global Constraints

以下为 CLAUDE.md 的项目级约束，**每个任务的要求都隐含包含本节**：

- **架构红线**：`:core` 不依赖 UI（不许 import Compose / `java.awt` / `javax.swing`）；UI 不许直接碰 adb / `CommandRunner`，只读 `DeviceRepository` 的 `StateFlow`、只回调它的方法；`:core` 不起真 adb，进程交互一律走 `AdbProcessRunner`（测试用 `FakeAdbProcessRunner`）；解析与执行分离。
- **技术债规范**：`:core` 所有 I/O 必须注入 Dispatcher（不许硬编码 `Dispatchers.IO`）；不留死代码（重构移除用法时同时删除声明）；跨线程可变状态必须 `@Volatile` 或 `Mutex.withLock` 或 `MutableStateFlow`。
- **`:core` 一律 TDD**：先写失败测试 → 验证失败 → 最小实现 → 验证通过 → 提交。不要事后补测试。
- **每个任务一个提交**，Conventional Commits：`feat(scope):` / `fix(scope):` / `test(scope):` / `chore(scope):`。
- **提交信息与代码用英文，与用户的对话用中文。**
- 测试命令：`./gradlew :core:test`、`./gradlew :desktop:test`；单类：`./gradlew :core:test --tests "*.LogcatControllerTest"`。
- 包根：`com.adbgui.core.*`（`:core`）、`com.adbgui.desktop.*`（`:desktop`）。
- **既有测试 harness 模式**（照抄，别自创）：`:core` 测试把 `runTest` 的 `this` 当注入 scope，每个用例末尾必须 `controller.stop()` / `repo.stop()`，否则 `runTest` 以 `UncompletedCoroutinesError` 失败。

### 关于"既有测试需要改"（重要，先读）

Task 3-8 会**故意改变**"选中设备即自动加载/连接"这一行为。现有若干测试正是**断言旧行为**的（例如 `LogcatViewModelTest.selected_serial_change_starts_logcat_and_lines_flow`、`CdpDebugViewModelTest.start_on_serial_select_connects`）。这些测试在对应任务里**必须改成先调 `vm.onPageEntered()`**——它们失败是预期的，不是回归。每个任务都包含"跑该 VM 的测试文件 → 把断言旧自动行为的用例补上 `onPageEntered()`"这一步。

**不要**为了让旧测试通过而保留 init 块 collector。

---

## 文件结构（按任务）

| 任务 | 文件 | 职责 |
|---|---|---|
| 1 | `CdpController.kt` + `CdpControllerTest.kt` | `_net` 环形上限 |
| 2 | `LogcatController.kt` + `LogcatControllerTest.kt`（+ `FakeAdbProcessRunner.kt` 加一个方法） | 增量节流发布 |
| 3 | `LogcatViewModel.kt` + `LogcatScreen.kt` + `LogcatViewModelTest.kt` | logcat 页面驱动 + null 停流修复 |
| 4 | `CdpDebugViewModel.kt` + `CdpDebugScreen.kt` + `CdpDebugViewModelTest.kt` | CDP 页面驱动 + 重进入修复 |
| 5 | `AppConsoleViewModel.kt` + `AppConsoleScreen.kt` + `AppConsoleViewModelTest.kt` | 页面驱动 |
| 6 | `DeviceInfoViewModel.kt` + `DeviceInfoScreen.kt` + `DeviceInfoViewModelTest.kt` | 页面驱动 |
| 7 | `FileExplorerViewModel.kt` + `FileExplorerScreen.kt` + `FileExplorerViewModelTest.kt` | 页面驱动 |
| 8 | `PortForwardingViewModel.kt` + `PortForwardingScreen.kt` + `PortForwardingViewModelTest.kt` | 页面驱动（保留 `collectLatest`） |
| 9 | `desktop/build.gradle.kts` | 打包堆参数 |

Task 1、2 是 `:core`（TDD 严格）。Task 3-8 是 `:desktop` 的 VM 状态机测试（沿用各文件已有 harness）。Task 9 是构建配置。

---

## Task 1: `CdpController._net` 补环形上限

`_net` 今天只做 `_net.value + e.req.copy(...)`，从未截断 → 无上限增长（实测 30 分钟 452 条，是真实但缓慢的泄漏）。

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/CdpController.kt`（构造参数 L54-61、`applyEvent` L288）
- Test: `core/src/test/kotlin/com/adbgui/core/device/CdpControllerTest.kt`

**Interfaces:**
- Consumes: 无（本任务最先做）
- Produces: `CdpController(transport, commands, logger, scope, ringCap: Int = 10000, netRingCap: Int = ringCap, clock: () -> Long = ...)` —— 新增第 6 个参数 `netRingCap`，默认继承 `ringCap`。既有调用点全部不受影响。

- [ ] **Step 1: 写失败测试**

在 `CdpControllerTest` 里改 `makeController` 暴露 `ringCap`，并加一条测试。

`makeController` 改为：

```kotlin
private fun makeController(
    transport: FakeCdpTransport,
    runner: FakeAdbProcessRunner,
    scope: kotlinx.coroutines.CoroutineScope,
    ringCap: Int = 10000,
): Pair<CommandRunner, CdpController> {
    val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
    val ctrl = CdpController(transport, cmd, NoopLogger, scope, ringCap = ringCap)
    return cmd to ctrl
}
```

新测试（放在 `network_events_merged_by_request_id` 之后）：

```kotlin
@Test
fun net_ring_caps_dropping_oldest() = runTest {
    val transport = FakeCdpTransport()
    val runner = FakeAdbProcessRunner()
    val (_, ctrl) = makeController(transport, runner, this, ringCap = 3)
    ctrl.connectManual(9222)
    advanceUntilIdle()
    (1..5).forEach { i ->
        transport.emit("""{"method":"Network.requestWillBeSent","params":{"requestId":"r$i","request":{"method":"GET","url":"http://x/$i"}}}""")
    }
    advanceUntilIdle()
    assertEquals(3, ctrl.networkRequests.value.size, "network ring must cap at netRingCap")
    assertEquals("http://x/3", ctrl.networkRequests.value.first().url)  // oldest 2 dropped
    assertEquals("http://x/5", ctrl.networkRequests.value.last().url)
    ctrl.stop()
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :core:test --tests "*.CdpControllerTest"
```

预期：`net_ring_caps_dropping_oldest` FAIL —— `expected:<3> but was:<5>`（`_net` 无上限）。

- [ ] **Step 3: 最小实现**

`CdpController` 构造参数里，在 `ringCap` 之后加一行：

```kotlin
    private val ringCap: Int = 10000,
    private val netRingCap: Int = ringCap,
```

`applyEvent` 的 `NetRequest` 分支（`CdpController.kt:288`）改成：

```kotlin
                is CdpEvent.NetRequest ->
                    _net.value = (_net.value + e.req.copy(timestamp = now)).takeLast(netRingCap)
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :core:test
```

预期：全绿（含既有的 `network_events_merged_by_request_id`、`clear_network_empties` 等）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/CdpController.kt core/src/test/kotlin/com/adbgui/core/device/CdpControllerTest.kt
git commit -m "fix(core): cap CdpController network ring at netRingCap

_net.value was appended to on every Network.requestWillBeSent with no
takeLast, so the list grew without bound (and every append copied the
whole list). ringCap only ever applied to _console."
```

---

## Task 2: `LogcatController` 增量节流发布

今天每收一行就 `_lines.value = filtered.toList()`（`LogcatController.kt:146`），是每行 O(N)，违反 `2026-08-17-logcat-design.md` §2 的"每行 O(1) 增量进视图"。

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatController.kt`
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/AdbProcessRunner.kt`（只在 `FakeAdbProcessRunner` 上加一个方法）
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatControllerTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `LogcatController(commands, logger, scope, ringCap: Int = 10000, publishIntervalMs: Long = 100)` —— 新增第 5 个参数；`Main.kt:75` 与 `LogcatViewModelTest` 的既有调用点不受影响（有默认值）。
  - `lines: StateFlow<List<LogcatLine>>` **签名不变** —— `LogcatScreen` 不需要改。
  - `FakeAdbProcessRunner.emitStreamLine(line: String)` —— 新增，向最近一次 `startStream` 打开的 channel 追加一行（用于给流"配速"）。

- [ ] **Step 1: 给 `FakeAdbProcessRunner` 加配速方法**

`AdbProcessRunner.kt` 的 `FakeAdbProcessRunner` 里，`startStream` 目前把 `ch` 建在函数内部（局部变量）。把最近一次的 channel 存下来：

```kotlin
    private var lastStreamChannel: Channel<String>? = null

    /** 向最近一次 `startStream` 打开的 channel 追加一行——让测试能给流"配速"，而不是
     *  一次性灌完。用于验证节流器在持续流下仍会周期性发布（不是被饿死的防抖）。 */
    fun emitStreamLine(line: String) { lastStreamChannel?.trySend(line) }
```

并在 `startStream` 里 `val ch = Channel<String>(Channel.UNLIMITED)` 之后加一行：

```kotlin
        lastStreamChannel = ch
```

（保留 `setStreamLines` / `setStreamLinesOnce` 原样不动——既有测试依赖它们。）

- [ ] **Step 2: 写失败测试**

改 `LogcatControllerTest` 的 `controller()` helper，暴露 `publishIntervalMs`：

```kotlin
    private fun controller(
        runner: FakeAdbProcessRunner,
        scope: kotlinx.coroutines.CoroutineScope,
        ringCap: Int = 5,
        publishIntervalMs: Long = 100,
    ): LogcatController {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        return LogcatController(cmd, NoopLogger, scope, ringCap = ringCap, publishIntervalMs = publishIntervalMs)
    }
```

加三条测试：

```kotlin
    @Test fun burst_of_many_lines_publishes_throttled_not_per_line() = runTest {
        // 2000 行一次性灌入（模型：logcat 连接时全量吐设备缓冲）。
        // 今天每行赋值一次 _lines → 2001 次 emission；节流后应 ≤ 4 次。
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines((1..2000).map { "08-17 10:23:45.100  100  200 I Tag$it: m$it" })
        val c = controller(runner, this, ringCap = 50000)
        var emissions = 0
        val collectJob = launch { c.lines.collect { emissions++ } }
        c.start("abc")
        advanceUntilIdle()
        assertEquals(2000, c.lines.value.size, "all lines must still reach the state")
        assertEquals("Tag2000", c.lines.value.last().tag)
        assertTrue(emissions <= 4, "expected throttled publishes for a 2000-line burst, got $emissions")
        c.stop(); collectJob.cancel()
    }

    @Test fun sustained_stream_keeps_publishing_not_starved() = runTest {
        // 每 30ms 来一行（快于 100ms 节流间隔）。若实现成防抖(collectLatest + delay)，
        // delay 会被每行取消 → 永不发布（饿死）。此测试锁死"节流而非防抖"。
        // 注意：这条在旧实现下也会通过（旧实现是每行发布）——它是新实现的行为守卫，不是红→绿。
        val runner = FakeAdbProcessRunner()
        val c = controller(runner, this)
        var emissions = 0
        val collectJob = launch { c.lines.collect { emissions++ } }
        c.start("abc"); runCurrent()
        repeat(20) { i ->
            runner.emitStreamLine("08-17 10:23:45.100  100  200 I Tag$i: m$i")
            runCurrent()
            advanceTimeBy(30)
        }
        assertTrue(emissions >= 3, "sustained stream must keep publishing, got $emissions")
        // 推进虚拟时间让最后一批也发布出去。注意 `advanceUntilIdle()` 在这里能正常返回：
        // `setStreamLines`/`emitStreamLine` 留下的是**未关闭**的 channel，runLoop 永久挂在该
        // `collect` 上、不会走到 backoff 的 `delay`，因此没有"永远排程中"的任务。
        advanceUntilIdle()
        assertEquals(20, c.lines.value.size)
        c.stop(); collectJob.cancel()
    }

    @Test fun ring_semantics_survive_batching() = runTest {
        // 7 行 / ringCap 5 → 最终只有最新 5 行，且顺序正确（批量发布不能破坏环形截断）。
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines((1..7).map { "08-17 10:23:45.00$it  100  200 I Tag$it: m$it" })
        val c = controller(runner, this)   // ringCap = 5
        var last: List<com.adbgui.core.domain.LogcatLine> = emptyList()
        val collectJob = launch { c.lines.collect { last = it } }
        c.start("abc")
        advanceUntilIdle()
        assertEquals(listOf("Tag3", "Tag4", "Tag5", "Tag6", "Tag7"), c.lines.value.map { it.tag })
        assertEquals(5, last.size, "last published snapshot must match the ring")
        c.stop(); collectJob.cancel()
    }
```

需要补的 import：`kotlinx.coroutines.launch`、`kotlinx.coroutines.test.runCurrent`、`kotlinx.coroutines.test.advanceTimeBy`、`kotlin.test.assertTrue`（`assertEquals` 已在）。

- [ ] **Step 3: 跑测试确认失败**

```bash
./gradlew :core:test --tests "*.LogcatControllerTest"
```

预期：`burst_of_many_lines_publishes_throttled_not_per_line` FAIL —— `expected throttled publishes ... got 2001`（`publishIntervalMs` 参数还不存在，编译先失败）。

- [ ] **Step 4: 实现**

`LogcatController.kt` 改动如下。

imports 加：

```kotlin
import kotlinx.coroutines.channels.Channel
```

构造参数加第 5 个（`LogcatController.kt:30-35`）：

```kotlin
class LogcatController(
    private val commands: CommandRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val ringCap: Int = 10000,
    /** `_lines` 的发布节流间隔。见 spec §4.2：必须是"固定间隔节流"而不是"取消式防抖"，
     *  否则持续洪流下 `delay` 会被不断取消、永不发布。 */
    private val publishIntervalMs: Long = 100,
) {
```

字段区（`ring` / `filtered` 旁边）加：

```kotlin
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var linesDirty = false
```

`start()` 改为（publisher 作为 `job` 的子协程——这样 `stop()` 能连带取消它，`runTest` 才收得了尾）：

```kotlin
    fun start(serial: String) {
        stop()
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
```

`stop()` 加一行清脏位：

```kotlin
    fun stop() {
        job?.cancel(); job = null
        stream?.kill(); stream = null
        linesDirty = false
        _status.value = LogcatStatus.IDLE
    }
```

`clear()` / `setFilters()` 改为置脏位后**直接发布**（不能只依赖 publishLoop——它是 `job` 的子协程，流停时已死）：

```kotlin
    fun clear() {
        scope.launch {
            mutex.withLock { ring.clear(); filtered.clear(); linesDirty = true }
            publishOnce()
        }
    }

    fun setFilters(f: LogcatFilters) {
        scope.launch {
            mutex.withLock { _filters.value = f; recomputeFiltered(); linesDirty = true }
            publishOnce()
        }
    }
```

`recomputeFiltered()` 去掉 `_lines` 赋值（改由调用方 `publishOnce()`）：

```kotlin
    private fun recomputeFiltered() {
        val f = _filters.value
        filtered.clear()
        val it = ring.iterator()
        while (it.hasNext()) { val l = it.next(); if (matches(l, f)) filtered.addLast(l) }
    }
```

新增两个发布函数：

```kotlin
    /** 唯一的 `_lines` 赋值路径——全部在 mutex 内快照 `filtered`。 */
    private suspend fun publishOnce() {
        mutex.withLock {
            if (linesDirty) { _lines.value = filtered.toList(); linesDirty = false }
        }
    }

    /** 固定间隔节流（不是防抖）：conflated channel 把 burst 塌缩成一次唤醒，`delay` 不被
     *  新信号取消，因此持续洪流下每 `publishIntervalMs` 必定发布一次。 */
    private suspend fun publishLoop() {
        for (signal in flushSignal) {
            delay(publishIntervalMs)
            publishOnce()
        }
    }
```

`onLine` 尾部改为（不再直接赋值 `_lines`）：

```kotlin
    private suspend fun onLine(line: LogcatLine) {
        if (_status.value == LogcatStatus.PAUSED) return
        mutex.withLock {
            ring.addLast(line)
            while (ring.size > ringCap) ring.removeFirst()
            if (matches(line, _filters.value)) {
                filtered.addLast(line)
                while (filtered.size > ringCap) filtered.removeFirst()
                linesDirty = true
            }
        }
        flushSignal.trySend(Unit)   // 非阻塞，永不挂起；放在锁外
    }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
./gradlew :core:test
```

预期：全绿。**关键是既有的 6 条 `LogcatControllerTest` 用例（start / ring_caps / pause / clear / stop / setFilters）必须全部仍然通过**——它们是这次重构的回归网。

若 `clear_empties_state_without_stopping` 失败，检查 `clear()` 里 `publishOnce()` 是否在 `mutex.withLock` **之外**调用（在锁内会死锁）。

- [ ] **Step 6: 提交**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/LogcatController.kt core/src/main/kotlin/com/adbgui/core/adb/AdbProcessRunner.kt core/src/test/kotlin/com/adbgui/core/device/LogcatControllerTest.kt
git commit -m "perf(core): throttle LogcatController line publishing

onLine assigned _lines.value = filtered.toList() per line, which is O(N)
per line and violates 2026-08-17-logcat-design.md section 2 ('每行 O(1)
增量进视图'). A connect burst of ~38k buffered device lines produced ~700M
element copies of young-gen garbage, which is what drove G1 to commit
1.16GB.

Publishing now goes through publishOnce() behind a conflated-channel
throttle (fixed interval, never a cancellable debounce). clear() and
setFilters() publish immediately since they are explicit user actions and
the publisher dies with the stream job."
```

---

## Task 3: Logcat 页面驱动加载 + null 停流修复

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatViewModel.kt`（L104 的 `refreshJob`、L105 的 `stop()`）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt`（`fun LogcatScreen` 开头，L48）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/LogcatViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `LogcatController`（签名不变）
- Produces: `LogcatViewModel.onPageEntered()`（`fun onPageEntered()`，无参无返回）——由 `LogcatScreen` 的 `LaunchedEffect(Unit)` 调用。`stop()` 语义不变（取消页面级 collector）。

- [ ] **Step 1: 写失败测试**

`LogcatViewModelTest` 加三条（沿用该文件既有构造方式：`CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})` + `LogcatController(cmd, NoopLogger, this, ringCap = 5)` + `LogcatViewModel(controller, selected, MutableStateFlow(true), this)`）：

```kotlin
    @Test fun no_load_before_page_entered() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>("abc")
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(true), this)
        advanceUntilIdle()
        assertEquals(0, vm.lines.value.size, "must not stream before the page is entered")
        vm.stop(); controller.stop()
    }

    @Test fun page_entered_starts_stream() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>("abc")
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(true), this)
        vm.onPageEntered()
        advanceUntilIdle()
        assertEquals(1, vm.lines.value.size)
        vm.stop(); controller.stop()
    }

    @Test fun serial_becomes_null_stops_stream() = runTest {
        // 修 CHANGELOG 记的既有债：今天 `it?.let{}` 跳过 null，旧设备的流会残留。
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I Tag: hi"))
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val controller = LogcatController(cmd, NoopLogger, this, ringCap = 5)
        val selected = MutableStateFlow<String?>("abc")
        val vm = LogcatViewModel(controller, selected, MutableStateFlow(true), this)
        vm.onPageEntered(); advanceUntilIdle()
        assertEquals(LogcatStatus.RUNNING, vm.status.value)
        selected.value = null
        advanceUntilIdle()
        assertEquals(LogcatStatus.IDLE, vm.status.value, "deselecting must stop the stream")
        vm.stop(); controller.stop()
    }
```

`serial_becomes_null_stops_stream` 需要 import `com.adbgui.core.device.LogcatStatus`。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :desktop:test --tests "*.LogcatViewModelTest"
```

预期：编译失败（`onPageEntered` 不存在）；且 `selected_serial_change_starts_logcat_and_lines_flow` 会因删除 init collector 而失败——**这是预期的行为变更**。

- [ ] **Step 3: 实现 VM**

`LogcatViewModel.kt` 最后两行：

```kotlin
    private val refreshJob: Job = scope.launch { selectedSerial.collect { it?.let { controller.start(it) } } }
    fun stop() { refreshJob.cancel() }
```

改为：

```kotlin
    private var pageJob: Job? = null

    /** 由 [LogcatScreen] 的 LaunchedEffect(Unit) 调用——页面可见时才挂 collector。
     *  `selectedSerial` 是 StateFlow，collect 会立即发出当前值，所以进入页面即对当前
     *  设备起流，不需要额外补一次调用。离开页面不停流（保留环形缓冲历史）。 */
    fun onPageEntered() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch {
            selectedSerial.collect { serial ->
                if (serial != null) controller.start(serial) else controller.stop()
            }
        }
    }

    fun stop() { pageJob?.cancel(); pageJob = null }
```

- [ ] **Step 4: 实现 Screen**

`LogcatScreen.kt` 的 `fun LogcatScreen(vm: LogcatViewModel, modifier: Modifier = Modifier) {` 之后，紧跟第一行加：

```kotlin
    // 页面可见才起流（spec §3）：离开页面不停流，缓冲历史保留。
    LaunchedEffect(Unit) { vm.onPageEntered() }
```

`LaunchedEffect` 与 `Unit` 在该文件已因他处使用而 import（若无则补 `androidx.compose.runtime.LaunchedEffect`）。

- [ ] **Step 5: 修既有测试**

`LogcatViewModelTest` 里所有断言"选中设备即起流"的用例（`selected_serial_change_starts_logcat_and_lines_flow`、`pause_resume_clear_forward_to_controller` 等），在创建 VM 之后、`advanceUntilIdle()` 之前插入 `vm.onPageEntered()`。**不要**改回 VM 实现。

- [ ] **Step 6: 跑测试确认通过**

```bash
./gradlew :desktop:test --tests "*.LogcatViewModelTest"
```

预期：全绿。

- [ ] **Step 7: 提交**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatViewModel.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/LogcatViewModelTest.kt
git commit -m "feat(desktop): start logcat stream on page entry, not on app launch

LogcatViewModel launched a collector in its init block, and Main.kt:101
auto-selects the first online device, so every app launch opened a live
adb logcat stream for a page the user never opened. The collector now
mounts in onPageEntered() (called from LogcatScreen's LaunchedEffect),
which is the same thing the page's composition lifetime already expresses.

Also fixes the CHANGELOG'd debt: serial -> null now stops the stream
instead of being skipped by it?.let{}."
```

---

## Task 4: CDP 页面驱动加载 + 重进入修复

今天 `CdpDebugViewModel` 的 init collector 在启动瞬间就开 CDP 会话（spec §4.5 的触发其实是按钮），且 `stop()` 会永久 `collector.cancel()`——访问过页面再离开后就再也自动连不上。

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/CdpDebugViewModel.kt`（L47-54 的 collector、L63-71 的 `stop()`）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/CdpDebugScreen.kt`（L85 `fun CdpDebugScreen` 开头；L120-121 的 `DisposableEffect` **保留不动**）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/CdpDebugViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `CdpController`
- Produces:
  - `CdpDebugViewModel.onPageEntered()` —— 挂页面级 collector（`collectLatest` 保证 A→B 快速切换时 A 的 in-flight start 被取消）。
  - `CdpDebugViewModel.stop()` —— 取消页面级 collector + `controller.stop()`（关 ws + `removeForward`）。
  - `CdpDebugViewModel.start()` 保持（顶栏「一键调试 WebView」按钮用）。

- [ ] **Step 1: 写失败测试**

`CdpDebugViewModelTest` 加两条（沿用 `makeVm` + `try/finally { vm.stop() }`）：

```kotlin
    @Test
    fun no_connect_before_page_entered() = runTest {
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val vm = makeVm(transport, runner, MutableStateFlow<String?>("s1"), this)
        try {
            advanceUntilIdle()
            assertNull(transport.connectUrl, "must not connect before the page is entered")
            assertEquals(CdpConnectionState.DISCONNECTED, vm.state.value)
        } finally { vm.stop() }
    }

    @Test
    fun reentering_the_page_reconnects() = runTest {
        // 修既有 bug：stop() 曾永久取消 collector，VM 又是应用级单例 →
        // "访问过 CDP 页 → 离开 → 再回来"后不再自动连。
        val runner = FakeAdbProcessRunner()
        scriptStartPrereqs(runner)
        val transport = FakeCdpTransport()
        val vm = makeVm(transport, runner, MutableStateFlow<String?>("s1"), this)
        try {
            vm.onPageEntered(); advanceUntilIdle()
            assertEquals(CdpConnectionState.CONNECTED, vm.state.value)
            vm.stop(); advanceUntilIdle()              // 离开页面
            assertEquals(CdpConnectionState.DISCONNECTED, vm.state.value)
            vm.onPageEntered(); advanceUntilIdle()      // 再次进入
            assertEquals(CdpConnectionState.CONNECTED, vm.state.value, "re-entry must reconnect")
        } finally { vm.stop() }
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :desktop:test --tests "*.CdpDebugViewModelTest"
```

预期：编译失败（`onPageEntered` 不存在）。既有 `start_on_serial_select_connects` 等用例也会失败——**预期的行为变更**。

- [ ] **Step 3: 实现 VM**

`CdpDebugViewModel.kt` 的 L47-71 区块：

```kotlin
    // The only long-lived collector: auto-start/stop on serial change. ...
    private val collector: Job = scope.launch {
        selectedSerial.collectLatest { serial ->
            if (serial != null) controller.start(serial) else controller.stop()
        }
    }

    fun start(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        controller.start(serial)
    }

    fun connectManual(port: Int): Job = scope.launch { controller.connectManual(port) }

    /** Stop the CDP session: ... */
    fun stop(): Job {
        collector.cancel()
        return scope.launch { controller.stop() }
    }
```

改为（把 `val collector` 换成可重建的页面级 `pageJob`，并新增 `onPageEntered()`；`start()` / `connectManual()` 原样保留）：

```kotlin
    // Page-scoped collector: mounted by onPageEntered() when the CDP page becomes visible,
    // cancelled by stop() when it goes away. A0: this used to be an init-block collector, so
    // every app launch auto-opened a CDP session (adb forward + two websockets with
    // Runtime/Page/Network/Log enabled) for a page the user never opened. `collectLatest`
    // keeps the A->B rapid-switch semantics: A's in-flight start is cancelled before B's runs.
    private var pageJob: Job? = null

    /** 由 [CdpDebugScreen] 的 LaunchedEffect(Unit) 调用。是重建而非"只挂一次"，所以
     *  "离开页面 → 再回来"能重新连上（旧实现 stop() 永久取消 collector，VM 又是应用级
     *  单例，导致第二次进入永不自动连）。 */
    fun onPageEntered() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch {
            selectedSerial.collectLatest { serial ->
                if (serial != null) controller.start(serial) else controller.stop()
            }
        }
    }

    fun start(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        controller.start(serial)
    }

    fun connectManual(port: Int): Job = scope.launch { controller.connectManual(port) }

    /** 离开页面：取消页面级 collector（覆盖结构化并发下的 run loop + transport），并
     *  `controller.stop()` 关闭 ws + 移除自建的 forward（一键模式）。 */
    fun stop(): Job {
        pageJob?.cancel(); pageJob = null
        return scope.launch { controller.stop() }
    }
```

- [ ] **Step 4: 实现 Screen**

`CdpDebugScreen.kt` 的 `fun CdpDebugScreen(` 函数体第一行加：

```kotlin
    // 页面可见才连（spec §3.4）；离开由下方 DisposableEffect 的 onDispose 收尾。
    LaunchedEffect(Unit) { vm.onPageEntered() }
```

**L120-121 的 `DisposableEffect(Unit) { onDispose { vm.stop() } }` 保持不动** —— CDP 的"离开即停"是刻意保留的（离开页面要收掉设备上的 9222 forward）。

- [ ] **Step 5: 修既有测试**

`CdpDebugViewModelTest` 里所有依赖 init collector 自动连的用例（`start_on_serial_select_connects`、`console_grows_when_device_emits_event` 等），在 `makeVm` 之后插入 `vm.onPageEntered()`。文件顶部的类注释（L23-30）描述了"auto-collector fires controller.start"，一并更新为 `onPageEntered()`。

- [ ] **Step 6: 跑测试确认通过**

```bash
./gradlew :desktop:test --tests "*.CdpDebugViewModelTest"
```

预期：全绿。

- [ ] **Step 7: 提交**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/CdpDebugViewModel.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/CdpDebugScreen.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/CdpDebugViewModelTest.kt
git commit -m "feat(desktop): connect CDP on page entry, and make re-entry work

The init-block collector auto-opened a CDP session (forward + two ws with
Network/Log domains enabled) at app launch, for a page the user never
opened - the spec (2026-08-31-cdp-debug-design.md section 4.5) triggers it
from the toolbar button, not from device selection.

stop() also cancelled that collector permanently, and the VM is an
app-scoped remember{} singleton, so after one visit the page never
auto-connected again. The collector now mounts in onPageEntered() and
stop() only cancels the page-scoped instance."
```

---

## Task 5: AppConsole 页面驱动加载

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt`（L197-198）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt`（`fun AppConsoleScreen(` 在 L77）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `AppConsoleViewModel.onPageEntered()`

- [ ] **Step 1: 写失败测试**

`AppConsoleViewModelTest` 加两条（沿用该文件 `vm(runner, selected, this)` helper）：

```kotlin
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
```

（`AppConsoleViewModel.packages` 是公开的 `StateFlow<List<PackageInfo>>`；`runner.runs` 是 `FakeAdbProcessRunner` 记录的每次 `run` 的完整 argv 列表。）

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :desktop:test --tests "*.AppConsoleViewModelTest"
```

预期：编译失败（`onPageEntered` 不存在）。

- [ ] **Step 3: 实现 VM**

`AppConsoleViewModel.kt` L197-198：

```kotlin
    private val refreshJob: Job = scope.launch { selectedSerial.collect { clearDetail(); load() } }
    fun stop() { refreshJob.cancel() }
```

改为：

```kotlin
    private var pageJob: Job? = null

    /** 由 [AppConsoleScreen] 的 LaunchedEffect(Unit) 调用——页面可见才加载（spec §3）。 */
    fun onPageEntered() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch { selectedSerial.collect { clearDetail(); load() } }
    }

    fun stop() { pageJob?.cancel(); pageJob = null }
```

- [ ] **Step 4: 实现 Screen**

`AppConsoleScreen.kt` 的 `fun AppConsoleScreen(` 函数体第一行加：

```kotlin
    LaunchedEffect(Unit) { vm.onPageEntered() }
```

- [ ] **Step 5: 跑该文件全量测试并修既有用例**

```bash
./gradlew :desktop:test --tests "*.AppConsoleViewModelTest"
```

断言"选中设备即自动列包"的用例补 `vm.onPageEntered()`；其余（显式调 `vm.install(...)` / `vm.load()` 的）应不受影响。预期最终全绿。

- [ ] **Step 6: 提交**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleViewModel.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/AppConsoleViewModelTest.kt
git commit -m "feat(desktop): load app console package list on page entry

The init-block collector ran pm list packages for the auto-selected
device at app launch, for a page the user never opened."
```

---

## Task 6: DeviceInfo 页面驱动加载

`DeviceInfoViewModel` 的页面是默认页 `DEVICE_OVERVIEW`（`DeviceOverviewScreen.kt:63` 内嵌 `DeviceInfoScreen`），所以行为与今天接近，只是时机从"VM 构造"挪到"页面组合"。

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceInfoViewModel.kt`（L63-64）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceInfoScreen.kt`（L54 `fun DeviceInfoScreen(`）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/DeviceInfoViewModelTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `DeviceInfoViewModel.onPageEntered()`

- [ ] **Step 1: 写失败测试**

本文件既有用例是内联构造（没有 helper），新增用例用下面这个 helper：

```kotlin
    /** 与 AppConsoleViewModelTest / FileExplorerViewModelTest 的 vm() helper 同形。 */
    private fun vm(
        runner: FakeAdbProcessRunner,
        selected: MutableStateFlow<String?>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<DeviceRepository, DeviceInfoViewModel> {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("di"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return repo to DeviceInfoViewModel(repo, selected, scope)
    }

    private val propOut =
        "[ro.product.model]: [Pixel 6]\n[ro.build.version.release]: [13]\n[ro.build.version.sdk]: [33]\n[ro.product.cpu.abi]: [arm64-v8a]\n"

    @Test fun no_load_before_page_entered() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, propOut, ""))
        val (repo, vm) = vm(runner, MutableStateFlow<String?>("abc"), this)
        advanceUntilIdle()
        assertNull(vm.props.value, "must not probe device props before page entry")
        assertTrue(runner.runs.none { it.contains("getprop") }, "no adb call before page entry")
        vm.stop(); repo.stop()
    }

    @Test fun page_entered_loads_props() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, propOut, ""))
        val (repo, vm) = vm(runner, MutableStateFlow<String?>("abc"), this)
        vm.onPageEntered(); advanceUntilIdle()
        assertEquals("Pixel 6", vm.props.value?.model)
        vm.stop(); repo.stop()
    }
```

需补 import：`kotlin.test.assertNull`、`kotlin.test.assertTrue`。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :desktop:test --tests "*.DeviceInfoViewModelTest"
```

预期：编译失败（`onPageEntered` 不存在）。

- [ ] **Step 3: 实现 VM**

`DeviceInfoViewModel.kt` L63-64：

```kotlin
    private val refreshJob: Job = scope.launch { selectedSerial.collect { load() } }
```

改为：

```kotlin
    private var pageJob: Job? = null

    /** 由 [DeviceInfoScreen] 的 LaunchedEffect(Unit) 调用——页面可见才加载（spec §3）。 */
    fun onPageEntered() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch { selectedSerial.collect { load() } }
    }
```

该文件若无 `stop()`，**加一个**（测试 teardown 必需）：

```kotlin
    fun stop() { pageJob?.cancel(); pageJob = null }
```

- [ ] **Step 4: 实现 Screen**

`DeviceInfoScreen.kt` 的 `fun DeviceInfoScreen(` 函数体第一行加：

```kotlin
    LaunchedEffect(Unit) { vm.onPageEntered() }
```

- [ ] **Step 5: 跑测试并修既有用例**

```bash
./gradlew :desktop:test --tests "*.DeviceInfoViewModelTest"
```

预期最终全绿（断言自动 load 的用例补 `vm.onPageEntered()`）。

- [ ] **Step 6: 提交**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceInfoViewModel.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceInfoScreen.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/DeviceInfoViewModelTest.kt
git commit -m "feat(desktop): probe device props on page entry

DeviceInfoViewModel now loads from the page's own composition lifetime
instead of its constructor. Its page is the default DEVICE_OVERVIEW, so
user-visible behaviour is unchanged - only the trigger moves."
```

---

## Task 7: FileExplorer 页面驱动加载

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerViewModel.kt`（L126-127）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerScreen.kt`（L34 `fun FileExplorerScreen(`）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/FileExplorerViewModelTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `FileExplorerViewModel.onPageEntered()`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test fun no_listing_before_page_entered() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0,
            "drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos\n", ""))
        val (repo, vm) = vm(runner, MutableStateFlow<String?>("abc"), this)
        advanceUntilIdle()
        assertTrue(runner.runs.none { it.contains("ls") }, "no ls before page entry")
        // _currentPath 初值就是 "/"（非 null），所以断言 entries 而不是 path。
        assertTrue(vm.entries.value.isEmpty(), "must not list before page entry")
        vm.stop(); repo.stop()
    }

    @Test fun page_entered_lists_root() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("ls", "-la"), AdbProcessResult(0,
            "drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos\n-rw-rw---- 1 root root 123 2020-01-01 12:00 test.txt\n", ""))
        val (repo, vm) = vm(runner, MutableStateFlow<String?>("abc"), this)
        vm.onPageEntered(); advanceUntilIdle()
        assertEquals("/", vm.currentPath.value)
        assertEquals(2, vm.entries.value.size)
        vm.stop(); repo.stop()
    }
```

（`vm(...)` helper、`currentPath` / `entries` 字段名、`ls -la` 的 fixture 字符串都照抄该文件既有用例。）

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :desktop:test --tests "*.FileExplorerViewModelTest"
```

预期：编译失败（`onPageEntered` 不存在）。

- [ ] **Step 3: 实现 VM**

`FileExplorerViewModel.kt` L126-127：

```kotlin
    private val refreshJob: Job = scope.launch { selectedSerial.collect { it?.let { navigate("/") } } }
    fun stop() { refreshJob.cancel() }
```

改为（原来的 `it?.let{}` 会跳过 null；本任务保持"换设备才导航"语义，只加页面门控）：

```kotlin
    private var pageJob: Job? = null

    /** 由 [FileExplorerScreen] 的 LaunchedEffect(Unit) 调用——页面可见才列目录（spec §3）。 */
    fun onPageEntered() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch { selectedSerial.collect { it?.let { navigate("/") } } }
    }

    fun stop() { pageJob?.cancel(); pageJob = null }
```

- [ ] **Step 4: 实现 Screen**

`FileExplorerScreen.kt` 的 `fun FileExplorerScreen(` 函数体第一行加：

```kotlin
    LaunchedEffect(Unit) { vm.onPageEntered() }
```

- [ ] **Step 5: 跑测试并修既有用例**

```bash
./gradlew :desktop:test --tests "*.FileExplorerViewModelTest"
```

预期最终全绿。

- [ ] **Step 6: 提交**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerViewModel.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerScreen.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/FileExplorerViewModelTest.kt
git commit -m "feat(desktop): list root directory on page entry

FileExplorerViewModel ran ls -la / for the auto-selected device at app
launch, for a page the user never opened."
```

---

## Task 8: PortForwarding 页面驱动加载

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingViewModel.kt`（L55-61）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingScreen.kt`（L44 `fun PortForwardingScreen(`）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/PortForwardingViewModelTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `PortForwardingViewModel.onPageEntered()`

**注意**：这个 VM 的 collector 用 `collectLatest` 且已经在 null 时清空（`else _forwards.value = emptyList()`）。**必须原样保留 `collectLatest` 语义**——既有测试 `refresh_cancels_stale_on_rapid_serial_switch`（I-1）锁的就是它。

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test fun no_refresh_before_page_entered() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "s1 tcp:8080 tcp:8080\n", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow<String?>("s1"), this)
        try {
            advanceUntilIdle()
            assertTrue(vm.forwards.value.isEmpty(), "must not refresh before the page is entered")
            assertTrue(runner.runs.none { it.contains("--list") })
        } finally { vm.stop(); repo.stop() }
    }

    @Test fun page_entered_refreshes() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "s1 tcp:8080 tcp:8080\n", ""))
        val (repo, vm) = makeVm(runner, MutableStateFlow<String?>("s1"), this)
        try {
            vm.onPageEntered(); advanceUntilIdle()
            assertEquals(1, vm.forwards.value.size)
        } finally { vm.stop(); repo.stop() }
    }
```

（`--list` 输出格式照抄该文件既有用例的录制字符串。）

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :desktop:test --tests "*.PortForwardingViewModelTest"
```

预期：编译失败（`onPageEntered` 不存在）。

- [ ] **Step 3: 实现 VM**

`PortForwardingViewModel.kt` L55-61：

```kotlin
    private val collector: Job = scope.launch {
        selectedSerial.collectLatest { serial ->
            if (serial != null) doRefresh(serial) else _forwards.value = emptyList()
        }
    }

    fun stop() = collector.cancel()
```

改为（把 `collector` 换成可重建的 `pageJob`；原有的注释块整体保留，只改承载变量与挂载时机）：

```kotlin
    private var pageJob: Job? = null

    /** 由 [PortForwardingScreen] 的 LaunchedEffect(Unit) 调用——页面可见才刷新（spec §3）。
     *  `collectLatest` 语义原样保留：快速 A→B 切换时 A 的 in-flight refresh 被取消。 */
    fun onPageEntered() {
        if (pageJob?.isActive == true) return
        pageJob = scope.launch {
            selectedSerial.collectLatest { serial ->
                if (serial != null) doRefresh(serial) else _forwards.value = emptyList()
            }
        }
    }

    fun stop() { pageJob?.cancel(); pageJob = null }
```

- [ ] **Step 4: 实现 Screen**

`PortForwardingScreen.kt` 的 `fun PortForwardingScreen(` 函数体第一行加：

```kotlin
    LaunchedEffect(Unit) { vm.onPageEntered() }
```

- [ ] **Step 5: 跑测试并修既有用例**

```bash
./gradlew :desktop:test --tests "*.PortForwardingViewModelTest"
```

断言自动刷新的用例补 `vm.onPageEntered()`；**I-1 那条 `collectLatest` 取消测试必须在补 `onPageEntered()` 后仍然通过**——若不过，说明 `collectLatest` 被改坏了。预期最终全绿。

- [ ] **Step 6: 提交**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingViewModel.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingScreen.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/PortForwardingViewModelTest.kt
git commit -m "feat(desktop): refresh port forwarding list on page entry

The init-block collector ran adb forward --list for the auto-selected
device at app launch, for a page the user never opened. collectLatest
semantics (I-1 stale-refresh cancellation) are preserved."
```

---

## Task 9: 打包 JVM 堆参数

未设 `-Xmx` 时 JVM 按物理内存自选（31.3GB → Initial 504MB / Max 7.8GB / MaxNewSize 4.8GB），G1 的 young gen 随启动 burst 撑大且基本不回还（`G1PeriodicGCInterval` 默认 0）。实测加 `-Xms64m -Xmx512m` 后，连续运行 30+ 分钟的堆提交从 1160MB 降到 140MB。

**Files:**
- Modify: `desktop/build.gradle.kts`（`compose.desktop.application { }` 块，L34-48）

**Interfaces:**
- Consumes: 无
- Produces: 无（构建配置）

- [ ] **Step 1: 加 jvmArgs**

`desktop/build.gradle.kts` 的 `compose.desktop.application {` 块内，`mainClass` 之后加：

```kotlin
    // 未设时 JVM 按物理内存自选（31.3GB → Initial 504MB / Max 7.8GB），G1 young gen 会随
    // 启动 burst 撑大且基本不回还（G1PeriodicGCInterval 默认 0）→ 任务管理器被顶到 1GB+。
    // 实测活跃堆仅 43MB（logcat 环 ≤50000 行）→ 512m 余量充足。见
    // docs/superpowers/specs/2026-09-16-page-driven-loading-and-logcat-publish-design.md §1.1.2。
    jvmArgs += listOf("-Xms64m", "-Xmx512m")
```

若 `jvmArgs` 在该 Compose 插件版本上是只读 `List`（编译期 `+=` 报错），改用 `jvmArgs("-Xms64m", "-Xmx512m")` 形式。

- [ ] **Step 2: 验证构建通过**

```bash
./gradlew :desktop:compileKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 3: 验证参数真的进了打包产物**

```bash
./gradlew :desktop:packageAppImage
grep -i "Xmx" "build/compose/binaries/main/app/AdbGui/app/AdbGui.cfg"
```

预期：输出包含 `java-options=-Xmx512m`（以及 `-Xms64m`）。

- [ ] **Step 4: 提交**

```bash
git add desktop/build.gradle.kts
git commit -m "chore(packaging): set -Xms64m -Xmx512m for the packaged app

With no heap flags the JVM sized itself from physical RAM (31.3GB ->
Initial 504MB, Max 7.8GB) and G1 grew young gen during the launch burst
without ever uncommitting, so Task Manager showed ~1.3GB while live heap
was ~45MB. Measured: heap commit after 30+ minutes drops 1160MB -> 140MB."
```

---

## 完成后的收尾

- [ ] 全量测试：`./gradlew :core:test :desktop:test` —— 全绿。
- [ ] 更新 `CHANGELOG.md`：新增一节记录本批四项（A/B/C/D），并**删除** logcat 节下那条已修的技术债（"取消选中（selectedSerial→null）不停 logcat 流"）。
- [ ] 手工验收按 spec §10.2 的 7 步在真机上跑一遍（尤其第 1 步"启动后 60s 内 adb 进程列表不应出现 logcat"与第 4 步"断开设备后 adb logcat 子进程应消失"）。

## 自检记录

- **spec 覆盖**：§3 → Task 3-8；§4 → Task 2；§5 → Task 1；§6 → Task 9；§7 测试计划 → 每个任务的 Step 1/2；§8 不做项 → 未出现在任何任务里（无 AppCDS、无持久化列表、无 CDP 批量化、无孤儿进程回收）；§10.2 手工验收 → 收尾清单。
- **类型一致性**：`onPageEntered()` 在 6 个 VM 上签名一致（无参、返回 `Unit`）；`pageJob` 在 6 个 VM 上同名同类型（`private var pageJob: Job?`）；`publishIntervalMs`（Task 2）与 `netRingCap`（Task 1）只在 `:core` 引入且带默认值，不破坏既有调用点；`LogcatController.lines` 签名全程不变，所以 Task 3 的 `LogcatScreen` 不需要改渲染代码。
- **占位符**：无。Task 6、7 原本省略的 harness 已补成可直接粘贴的完整代码（含 `DeviceInfoViewModelTest` 新增的 `vm()` helper 与 `propOut` fixture、`FileExplorerViewModelTest` 复用的既有 `vm()` helper 与 `ls -la` fixture 字符串）。
- **测试可终止性核查**：所有 `:core` 用例都用 `setStreamLines` / `emitStreamLine`（**不关闭** channel），因此 `runLoop` 永久挂在 `collect` 上、不会走到 backoff 的 `delay`，`advanceUntilIdle()` 能正常返回；每个用例末尾 `c.stop()` / `collectJob.cancel()` 收尾。