# ADB GUI — 页面驱动加载 + logcat 增量发布 设计

- **日期**：2026-09-16
- **状态**：设计草案，待用户复核后转 writing-plans
- **范围**：四项 —— A(6 个 VM 改页面驱动加载) + B(`LogcatController` 增量发布，修 spec 违约) + C(`CdpController._net` 补环形上限) + D(打包补 JVM 堆参数兜底)
- **依据**：2026-09-16 的真机实测（§1.1）；`docs/superpowers/specs/2026-08-17-logcat-design.md` §2 / `2026-08-31-cdp-debug-design.md` §4.4-§4.5；CLAUDE.md 架构红线与技术债规范
- **参照既有实现**：`LogcatController`/`LogcatViewModel`、`CdpController`/`CdpDebugViewModel`、`AppShell` 的 `when(page)` 单页组合、`desktop/build.gradle.kts` 的 `compose.desktop.application`

## 1. 背景与动机

用户报告两个现象：冷启动 exe 约 5s+（杀掉后重启约 3s）；启动后任务管理器显示占用约 1249MB 内存。

排查后确认：**启动耗时基本正常（§1.1.1），但内存 1.2GB 不正常且可归因到代码**。同时发现一个**既有 spec 违约**和一处**无上限增长**。

### 1.1 实测证据

#### 1.1.1 启动耗时

| 场景 | 实测 |
|---|---|
| 热启动（进程被杀后立即重启） | **2.24s / 2.13s** 到窗口可见 |
| 用户自己的一次重启（日志时间戳） | 进程创建 `11:06:44` → `ADB GUI starting` `11:06:46.037` = **2.04s** |
| 我实测的冷启动（刚杀完立刻起） | 8.41s |

载荷：`app/` 102MB（60+ 个 jar）+ `runtime/` 75MB。运行时的 CDS 是关的（`jcmd VM.metaspace` 报 `CDS: off`）。

**结论**：2s 是 JVM + Compose + Skiko 的实际地板；冷启动多出的几秒来自磁盘冷读 + Defender 逐个扫描 jar。**属于正常范围**，D 项只做兜底，不做激进优化。

#### 1.1.2 内存（同一进程、完全空闲、无任何用户操作）

| 时刻 | Working Set |
|---|---|
| 窗口刚出现 | 289.6 MB |
| +5s | 991.4 MB |
| +15s | 1094.9 MB |
| +30s | 1372.7 MB |

`jcmd GC.heap_info`（t+30s）：

```
garbage-first heap   total 1187840K, used 219833K     ← 提交 1160MB，其中 200MB 是 young gen 垃圾
Metaspace            used 48654K, committed 49152K
```

强制 full GC 后的存活对象**合计仅约 45MB**（`GC.class_histogram`），其中 `LogcatLine` 38806 个、`String` 196276 个、`byte[]` 200860 个。

**这不是内存泄漏，是"堆提交了 1.16GB 却只用了 45MB"。**

未设 `-Xmx` 时 JVM 按物理内存（31.3GB）自选：

```
InitialHeapSize=504MB   MaxHeapSize=7.8GB   MaxNewSize=4.8GB
```

G1 的 young gen 自适应会把它撑大，而 `G1PeriodicGCInterval` 默认 0（不回收已提交内存）→ 提交了就基本不还。

对照实验（`-Xms64m -Xmx512m`，同一份 exe）：

| 场景 | 无上限 | 加 `-Xms64m -Xmx512m` |
|---|---|---|
| t+30s | 1372.7 MB | 289.5 MB |
| t+60s | — | 615.1 MB |
| 连续运行 30+ 分钟后堆提交 | 1160 MB | **140 MB**（used 43MB，`LogcatLine` 37663，`CdpNetworkRequest` 452，`CdpConsoleEntry` 220） |

#### 1.1.3 推高堆的是"启动即开的两条长连接 + 每行 O(N) 发布"

`selectedSerial` 在启动时被 `Main.kt:101` 自动填为第一台在线设备，而 6 个 ViewModel 都**在 `application{}` 里被 `remember{}` 提前构造**（`Main.kt:56-90`），且各自在 init 块里挂了一个长生命周期 collector：

| VM | 位置 | 选中设备时自动做的事 |
|---|---|---|
| `LogcatViewModel` | `:104` `selectedSerial.collect { it?.let { controller.start(it) } }` | 开**持续 `adb logcat` 流** |
| `CdpDebugViewModel` | `:50-52` `collectLatest { if (serial != null) controller.start(serial) }` | 开 **CDP 会话**（forward + 两个 ws，启用 `Runtime/Page/Network/Log` 四域） |
| `AppConsoleViewModel` | `:197` `collect { clearDetail(); load() }` | `pm list packages` |
| `DeviceInfoViewModel` | `:63` `collect { load() }` | `getprop` 等 |
| `FileExplorerViewModel` | `:126` `collect { it?.let { navigate("/") } }` | `ls -la /` |
| `PortForwardingViewModel` | `:55` `collectLatest { ... }` | `forward --list` |

`AppShell` 用 `when(page)` **一次只组合一个页面**（`AppShell.kt:170-210`），默认页是 `DEVICE_OVERVIEW`。所以启动时真正白跑的是 **logcat / CDP / AppConsole / FileExplorer / PortForwarding 这 5 个**（DeviceInfo 属于默认页，合法）。

其中 logcat 最致命——`LogcatController.kt:146`：

```kotlin
private suspend fun onLine(line: LogcatLine) {
    if (_status.value == LogcatStatus.PAUSED) return
    mutex.withLock {
        ring.addLast(line)
        while (ring.size > ringCap) ring.removeFirst()
        if (matches(line, _filters.value)) {
            filtered.addLast(line)
            while (filtered.size > ringCap) filtered.removeFirst()
            _lines.value = filtered.toList()      // ← 每行全量复制
        }
    }
}
```

每行都 `filtered.toList()`，而 `filtered` 最大到 `ringCap`（实测配置 50000）。

两个独立实例的 `GC.class_histogram` 都显示环内**约 3.7-3.9 万行**，且都**未触到 50000 上限**：

| 实例 | 采样时刻 | `LogcatLine` 实例数 |
|---|---|---|
| 无堆上限那次 | 启动后约 35s | **38806** |
| 加 `-Xmx512m` 那次 | 启动后约 35min | **37663** |

两次数量接近、且 35 分钟后略少于 35 秒时——说明行**不是持续高速产生的**，而是 `adb logcat` 连接时先把**设备端已缓冲的日志全量吐出**（约 3.7-3.9 万行），随后设备基本安静。（这是对观测数据的推断；机制本身值得在实现时用 DEBUG 日志确认，但它不影响 O(N²) 的结论：环里有多少行，每次发布就要复制多少元素。）

于是启动后这 3.8 万行在很短时间内灌入 → 3.8 万次发布 × 平均 1.9 万元素 ≈ **7 亿次元素复制**，全部落在 young gen 里 → G1 撑大 young gen → 提交飙到 1.16GB。

**这条同时是 spec 违约。** `docs/superpowers/specs/2026-08-17-logcat-design.md` §2 的决策表写的是：

> | 过滤计算 | 每行 **O(1) 增量进视图**；过滤条件变更 O(N) 重算 |

实现是每行 O(N)。所以 B 项不是"优化"，是**把实现拉回它自己 spec 的要求**。

#### 1.1.4 附带发现

- `CdpController.kt:288` `_net.value = _net.value + e.req.copy(...)` —— **无 `takeLast`**，`ringCap` 只用在 `_console`（`:286`）上。网络请求条目无限累积，且每加一条都是 O(N) 全量复制。实测 30 分钟 452 条，**速率慢、不是本次 1.2GB 的主因**，但确是无上限增长，同类必修。
- 应用被强杀时 `adb logcat` 子进程会被遗留（实测发现 5 个父进程已死的孤儿进程）。
- CHANGELOG 已记录相关已知债：*"取消选中（selectedSerial→null）不停 logcat 流 → 旧设备后台流残留（一行修）"*。

### 1.2 目标

1. 启动后**不产生任何用户没要求的后台长连接**——点开哪个页面，才加载/连接那个页面需要的东西。
2. `LogcatController` 恢复 spec 要求的每行 O(1)，去掉 O(N²) 垃圾源。
3. `CdpController._net` 有上限。
4. 打包带堆参数兜底，避免"提交了不还"把占用顶到 1GB+。

### 1.3 非目标

- 不做冷启动激进优化（AppCDS / 瘦身 jar）。§1.1.1 已证明 2s 是地板，收益不匹配风险。
- 不改 `Main.kt:101` 的"自动选中第一台在线设备"——它让默认页有内容，是合理 UX；A 项生效后它不再触发任何后台工作。
- 不改 CDP 的"一键『调试 WebView』按钮"（spec §4.5 既有交互）。

## 2. 架构红线（逐条确认）

1. **`:core` 不依赖 UI** —— B/C 项只改 `:core` 内部实现，不引入 Compose/awt/swing。✅
2. **UI 不直接碰 adb** —— A 项只是把"何时调 `load()`/`start()`"从 init 块挪到页面组合，调用对象仍是 `DeviceRepository` / `:core` controller，无越层。✅
3. **`:core` 不起真 adb** —— B/C 项测试继续走 `FakeAdbProcessRunner`。✅
4. **平台差异藏接口背后** —— 本批不涉及。✅
5. **解析与执行分离** —— 本批不新增 parser。✅
6. **§3 跨线程可变状态必须标记** —— 新增的 `linesDirty` / `everEntered` 跨线程读写，必须 `@Volatile` 或 `MutableStateFlow`。见 §3.2 / §4.2。
7. **§1 I/O 必须注入 Dispatcher** —— B 项不新增 I/O，不涉及。
8. **§2 不留死代码** —— A 项删除的 init 块 collector 与 `stop()` 保留语义；B 项替换的旧发布路径要删干净。

## 3. A — 页面驱动加载

### 3.1 机制

`AppShell` 的 `when(page)`（`AppShell.kt:170-210`）保证"页面可见 ⇔ 该页 Screen 在组合树里"。因此让每个 Screen 在顶层声明进入：

```kotlin
LaunchedEffect(Unit) { vm.onPageEntered() }
```

`LaunchedEffect(Unit)` 随 Screen 组合执行一次；离开页面时 Screen 卸载，下次回来重新执行——语义正好是"每次进入页面"。

### 3.2 核心手法：把 collector 从 init 块挪到 `onPageEntered()`

6 个 VM 今天都在 init 块里挂长生命周期 collector（`private val refreshJob = scope.launch { selectedSerial.collect { ... } }`）。改为页面级：

```kotlin
private var pageJob: Job? = null

/** 由对应 Screen 的 LaunchedEffect(Unit) 调用——页面可见时才挂 collector。 */
fun onPageEntered() {
    if (pageJob?.isActive == true) return     // 防重复进入时叠加 collector
    pageJob = scope.launch {
        selectedSerial.collect { serial -> /* 原先的动作，见 §3.3 */ }
    }
}
```

`selectedSerial` 是 `StateFlow`，`collect` **会立即发出当前值** → 进入页面即对当前设备执行一次，不需要额外补一次显式调用。

**不需要新增 `everEntered` 标志**：collector 的存在本身就编码了"页面是否可见"，因此也不引入新的跨线程 `var`（CLAUDE.md §3 的可见性问题自然消失）。

`stop()` 保留（取消 `pageJob`），原因有两层：
- 它是测试 teardown 的必需品——不取消长生命周期子协程，`runTest` 会以 `UncompletedCoroutinesError` 失败。`AppConsoleViewModelTest` 每条用例都调 `vm.stop()`，这是纯测试用的钩子。
- CDP 页的离开钩子也用它（§3.4）。

### 3.3 每个 VM 的动作

| VM | 页面 | collector 动作（进页面立即执行一次；设备切换时再执行） | 离开页面 |
|---|---|---|---|
| `AppConsoleViewModel` | `APP_CONSOLE` | `clearDetail(); load()` | 不停 |
| `DeviceInfoViewModel` | `DEVICE_OVERVIEW`（默认页） | `load()` | 不停 |
| `FileExplorerViewModel` | `FILE_EXPLORER` | `if (serial != null) navigate("/")` | 不停 |
| `PortForwardingViewModel` | `PORT_FORWARDING` | 保留 `collectLatest { if (serial != null) doRefresh(serial) else _forwards.value = emptyList() }` 语义不变 | 不停 |
| `LogcatViewModel` | `LOGCAT` | `if (serial != null) controller.start(serial) else controller.stop()` ← **顺带修 §1.1.4 的既有债**（今天 `it?.let{}` 跳过 null，留下旧设备的残留流） | 不停（保留历史缓冲，用户已确认） |
| `CdpDebugViewModel` | `CDP_DEBUG` | 同 logcat | **停**（见 §3.4） |

`DeviceInfoViewModel` 的页面是默认页 `DEVICE_OVERVIEW`（`DeviceOverviewScreen.kt:63` 内嵌 `DeviceInfoScreen`），行为与今天等价（今天也是启动瞬间 load），只是时机从"VM 构造"挪到"页面组合"。

### 3.4 CDP 保持"离开即停"，并顺手修掉一个既有 bug

`CdpDebugScreen.kt:120-121` 已有：

```kotlin
DisposableEffect(Unit) { onDispose { vm.stop() } }
```

**保留该行为**，理由：
- CDP 的 `stop()` 会 `removeForward(serial, ForwardSpec(TCP, "9222"))`——离开页面就该把建在设备上的 forward 收掉，而不是让 9222 一直挂着；
- 与 logcat 不同，CDP 是"一次性调试会话"，没有"保留历史"的价值主张；再次进入重新连即可。

**但现有实现有一个 bug**：`CdpDebugViewModel.stop()` 会 `collector.cancel()` 且**不可恢复**。而 CDP VM 在 `Main.kt:90` 是 `remember{}` 的应用级单例，于是"访问过 CDP 页 → 离开 → 再回来"之后不再自动连上，设备切换也不再重连。代码注释自己承认了这点（`CdpDebugViewModel.kt:65-67`：*"After [stop] the VM no longer auto-restarts on serial change — recreate the VM (or don't call stop()) to keep the collector alive"*），但实际不存在 recreate 的路径。

迁到 §3.2 的 `onPageEntered()` 模式后**自然修复**：`onDispose → stop()` 取消页面级 collector，再次进入时 `LaunchedEffect(Unit) → onPageEntered()` 重建它。

> **与用户所选选项的一处偏差（需确认）**：选项文案写的是"离开页面继续跑（保留历史 / 不断连）"，该句对 logcat 成立、对 CDP 不成立——CDP 已有且值得保留"离开即停"。偏差方向是"更少后台占用"，与选项意图一致，但实现细节与文案不同，故在此显式标出。

### 3.5 取舍

进入页面时**多一次 loading**——数据不再预热（`busy` 态走既有 UI，无新增）。CDP 页更明显：从"启动就已连上"变成"进页面才连"。

换来的收益：**启动后零后台 adb 流量、零后台长连接**——今天启动瞬间会同时开出 logcat 流 + CDP 会话（含 forward + 两个 ws）+ 4 条一次性查询。

## 4. B — `LogcatController` 增量发布

### 4.1 目标不变式

- **每行处理 O(1)**：`onLine` 只做 `ring.addLast` / `filtered.addLast`（均为均摊 O(1)）与一次非阻塞信号发送。
- **`_lines` 的发布被节流**：发布次数与"经过的时间"成正比，与"到达的行数"无关。
- **`lines: StateFlow<List<LogcatLine>>` 契约不变** —— UI（`LogcatScreen` / `LazyColumn`）零改动。

附带收益：`collectAsState` 的 Compose 重组从约 1300 次/秒降到 ≤10 次/秒（今天每行发布一次 = 每行一次重组，本身也是 CPU 灾害点）。

### 4.2 发布器设计（节流，非防抖）

用 **conflated channel + 固定间隔**，而不是 `collectLatest { delay() }`：

```kotlin
// 常量：构造参数，默认 100ms，测试可注入
private val publishIntervalMs: Long = 100
private val flushSignal = Channel<Unit>(Channel.CONFLATED)
@Volatile private var linesDirty = false

private suspend fun publishLoop() {
    for (signal in flushSignal) {          // CONFLATED：burst 塌缩成一次唤醒
        delay(publishIntervalMs)           // 固定间隔节流（非取消式防抖）
        mutex.withLock {
            if (linesDirty) { _lines.value = filtered.toList(); linesDirty = false }
        }
    }
}
```

`onLine` 的尾部变成：

```kotlin
if (matches(line, _filters.value)) {
    filtered.addLast(line)
    while (filtered.size > ringCap) filtered.removeFirst()
    linesDirty = true
    flushSignal.trySend(Unit)              // 非阻塞，永不挂起
}
```

**必须是节流而非防抖**：若用 `collectLatest { delay() }`，持续不断的行会让 `delay` 不断被取消 → 洪流期间永不发布（饿死）。`for (signal) { delay() }` 保证洪流下**至多每 100ms 发布一次**。

- **burst 场景**：连接时设备缓冲约 38806 行灌入 → 约 20 次发布（每 100ms 一次），总复制量约 76 万元素（今天约 7 亿次），**降低约 1000 倍**。
- **细流场景**：1 行/秒 → 每行后约 100ms 发布一次，实时观感不变。
- **静止场景**：consumer 挂在 `receive()` 上，不持有任何已排程任务。

`clear()` / `setFilters()` 改为只置 `linesDirty = true` + `trySend`，由 publishLoop 统一发布 —— **`_lines` 全程只有一个写入者**，消除今天 `clear()` 与 `onLine()` 并发赋值的竞争。

`recomputeFiltered()` 保持 O(N)（spec 明确允许"过滤条件变更 O(N) 重算"），只是不再自己赋值 `_lines`。

### 4.3 生命周期与可测性

`publishLoop` 作为 `job` 的**子协程**在 `start()` 里启动：

```kotlin
fun start(serial: String) {
    stop()
    job = scope.launch {
        launch { publishLoop() }
        mutex.withLock { ring.clear(); filtered.clear(); linesDirty = true; _error.value = null }
        runLoop(serial)
    }
}
```

这样做是为了不让测试挂死：`runTest` 会等注入 scope 上的子协程结束，若把 publishLoop 挂在 init 块里，它就永远不会结束（`UncompletedCoroutinesError`）。挂在 `job` 下则由 `stop()` 的 `job.cancel()` 连带取消，与既有 test 的 `c.stop()` 收尾模式一致。

同时 `advanceUntilIdle()` 仍可用：consumer 挂在 channel `receive()` 上时**没有已排程任务**，`advanceUntilIdle()` 能正常返回（对比：`while(true){delay()}` 的 ticker 循环会让 `advanceUntilIdle()` 无限推进，绝不能这么写）。

## 5. C — `CdpController._net` 补环形上限

`CdpController` 构造参数加 `private val netRingCap: Int = ringCap`（Kotlin 允许默认值引用前一个参数），`applyEvent` 里：

```kotlin
is CdpEvent.NetRequest ->
    _net.value = (_net.value + e.req.copy(timestamp = now)).takeLast(netRingCap)
```

**刻意不做批量化**：实测 30 分钟仅 452 条网络条目，收益不足以支撑改动面。`_console` 已有 `takeLast(ringCap)`，不动。

`CdpControllerTest` 需补一条"超 `netRingCap` 后截断"的测试——**当前实现必然失败（先红）**，这是本项验收的核心断言。

## 6. D — 打包 JVM 堆参数兜底

`desktop/build.gradle.kts` 的 `compose.desktop.application` 块：

```kotlin
compose.desktop.application {
    mainClass = "com.adbgui.desktop.main.MainKt"
    // 未设时 JVM 按物理内存自选（31.3GB → Initial 504MB / Max 7.8GB），G1 young gen 会
    // 随启动 burst 撑大且基本不回还（G1PeriodicGCInterval 默认 0）→ 占用被顶到 1GB+。
    // 实测活跃堆仅 43MB（logcat 环 ≤50000 行）→ 512m 余量充足。
    // 见 docs/superpowers/specs/2026-09-16-page-driven-loading-and-logcat-publish-design.md §1.1.2。
    jvmArgs += listOf("-Xms64m", "-Xmx512m")
    nativeDistributions {
        targetFormats(TargetFormat.Msi, TargetFormat.AppImage)
        packageName = "AdbGui"
        packageVersion = "1.0.0"
        windows { /* 既有配置不变 */ }
    }
}
```

（`jvmArgs` 在 Compose Desktop DSL 里是可变列表，`+=` 即可。若该属性在某版本上是只读 `List`，改成构造式的 `jvmArgs("-Xms64m", "-Xmx512m")` 形式，编译期即可发现。）

- `-Xms64m`：避免启动即提交 504MB（`InitialHeapSize` 也是按物理内存算的）。
- `-Xmx512m`：同时把 `MaxNewSize` 压到约 307MB（G1 强制 ≤ 60% × max heap）。
- 注意：改 `build.gradle.kts` **只影响重新打包的产物**；已装在 `D:\Program Files\AdbGui\` 的 exe 需重新打包 + 安装才生效。验证时优先用 `./gradlew :desktop:run`（该路径同样吃到 `jvmArgs`）或 `packageAppImage`。

## 7. 测试计划

### 7.1 `:core`（TDD，先红后绿）

`LogcatControllerTest` 新增：

1. `publish_is_throttled_not_per_line` —— 喂 N 行并沿虚拟时间推进 T 毫秒，收集 `lines` 的 emission 次数，断言 `emissions <= T / publishIntervalMs + 2`（今天必然是 N 次 → 先红）。同时断言最终 `lines.value` 内容与行序完全正确。
2. `burst_of_many_lines_publishes_once_per_interval` —— 一次灌入 ≫ `ringCap` 的行，断言最终 `lines.value.size == ringCap` 且内容是最新的 `ringCap` 行（环形截断语义未被批量发布破坏）。
3. `clear_and_setFilters_publish_through_single_writer` —— `clear()` 后 `lines` 变空；`setFilters` 后 `lines` 按新过滤条件重算（且发生在 `advanceUntilIdle()` 之后）。
4. 既有 6 条测试（start/ringCap/pause/clear/stop/setFilters）必须**全部保持通过**——它们是这次重构的回归网。

`CdpControllerTest` 新增：

5. `net_ring_caps_at_netRingCap_dropping_oldest` —— **当前实现必红**。

### 7.2 `:desktop`（VM 状态机单测）

每个 VM 各加三条断言：

1. **未进入页面前**：`selectedSerial` 变为非空，**不触发任何** repo 调用或 `controller.start`（今天必然触发 → 先红）。
2. **进入页面后**：`onPageEntered()` 触发一次加载/连接（`StateFlow.collect` 立即发当前值）。
3. **设备切换**：`onPageEntered()` 之后切换 serial，按 §3.3 表格语义重新加载/重起；`PortForwardingViewModel` 保留 `collectLatest` 的取消语义（沿用既有 I-1 测试）。

两条针对既有缺陷的断言：

4. `LogcatViewModel` — `onPageEntered()` 后 serial 变 null，断言 `controller.stop()` 被调用（修 §1.1.4 的债）。
5. `CdpDebugViewModel` — `onPageEntered()` → `stop()` → 再 `onPageEntered()`，断言**第二次进入仍会连接**（修 §3.4 的 collector 不可恢复 bug）。

既有测试的收尾模式（`vm.stop()` / `controller.stop()` in `finally`）保持不变——这也是为什么 §3.2 必须保留 `stop()`。

### 7.3 打包验证

`./gradlew :core:test :desktop:test` 全绿后，跑 `packageAppImage`，确认生成的 `AdbGui.cfg` 里出现 `java-options=-Xms64m` / `-Xmx512m`。

## 8. 不做（YAGNI）

- AppCDS / jlink 瘦身 / 减少 jar 数量（冷启动优化，独立评估）。
- `LogcatController` 换用持久化列表或 delta 流（方案 B/C，§决策已排除）。
- CDP console/network 的批量化（无实测收益）。
- 给 `_console.value = (_console.value + ...).takeLast(...)` 做缓存友好改写（有上限、实测无压力）。
- 修 `CdpController` 里 `_net.value.map { ... }` 的 O(N) 更新（有上限后 N ≤ `netRingCap`，实测无压力）。
- 回收应用强杀时遗留的孤儿 `adb logcat` 子进程（独立问题，且需先确认进程归属策略）。

## 9. 风险与取舍

| 风险 | 说明 | 缓解 |
|---|---|---|
| A 项改变首屏体验 | 切页多一次 loading；CDP 页从"启动已连"变"进页面才连" | 用户已确认该取舍；loading 走既有 `busy` 态，无新 UI |
| A 项遗漏页面 | 6 个 VM 都要改，漏一个就退化为"启动即加载" | §7.2 每条 VM 都有"未进入页面不触发"的断言，漏改必红 |
| collector 重复进入叠加 | `onPageEntered()` 被调用两次会挂两个 collector → 重复加载 | `if (pageJob?.isActive == true) return` 守卫 + §7.2-5 的重进入测试 |
| CDP 离开钩子丢失 | 若 `CdpDebugScreen` 的 `DisposableEffect` 被误删，CDP 会话会留在后台 | §3.4 明写保留；§10.2-6 有"离开 CDP 页后 forward 应消失"的手工验收 |
| B 项节流引入显示延迟 | ≤100ms | 看日志场景无感；若实测不适，调小 `publishIntervalMs`（已是构造参数） |
| B 项 publishLoop 挂错位置 | 挂 init 块 → 所有 `runTest` 挂死 | §4.3 明确挂 `job` 下，并要求既有 6 条测试全绿 |
| `-Xmx512m` 过小 | 极端场景（满 50000 行环 + 大量页面数据）可能 OOM | 实测活跃堆 43MB，余量 10 倍以上；§10.2-5 有满环压力验收 |

## 10. 验收

### 10.1 自动化

`./gradlew :core:test`、`./gradlew :desktop:test` 全绿，且 §7.1-5、§7.2 的新断言在实现前后确实是红→绿。

### 10.2 手工（真机，用户环境）

1. **启动静默**：杀干净 AdbGui（含遗留 `adb logcat` 子进程），启动 exe，**不做任何操作**，观察 60s：
   - 任务管理器内存应远低于 1.3GB（加 D 项后预期 300-600MB 区间）；
   - `adb` 进程列表中**不应出现 `logcat`**（CDP 未打开 → 也不应有 forward）；
2. **页面驱动**：逐个点开 应用管理 / 日志 / 文件 / 端口转发 / CDP，各自出现一次 loading 后正常出数据；
3. **logcat 洪流**：进入日志页，确认首屏 burst（数千行）期间 UI 不卡、内存不飙；暂停/清空/过滤/导出仍正确；
4. **设备切换**：进入日志页后切换到另一台设备，流应重起；**取消选中（断开设备）后 `adb logcat` 子进程应消失**（今天不会）；
5. **满环压力**：日志页挂到 50000 行上限，确认无 OOM、内存稳定；
6. **CDP 上限与离开即停**：进入 CDP 页连上后离开页面，确认 `adb forward --list` 里 9222 已消失、无 `adb logcat` 以外的新增长连接；再进入应能重新连上（修 §3.4 的 bug）。会话持续一段时间后 `jcmd GC.class_histogram` 确认 `CdpNetworkRequest` 实例数不超过 `netRingCap`；
7. **重复启动无残留**：正常关闭（点 X）再启动，确认 `adb logcat` 子进程不累积。

## 11. 与既有文档的关系

- `2026-08-17-logcat-design.md` §2 的"每行 O(1) 增量进视图"是 B 项的依据；本 spec 不修改该文档，B 项实现后该文档的决策表重新成立。
- `2026-08-31-cdp-debug-design.md` §4.5 把 CDP 触发设计为顶栏按钮；`CdpDebugViewModel` init 块的自动 start collector 不在该 spec 内，A 项将其删除，恢复 spec 语义（按钮 + 进页面触发）。
- CHANGELOG 记录的 logcat 已知债（"取消选中不停流"）在 A 项中一并修掉，交付后应更新 CHANGELOG。