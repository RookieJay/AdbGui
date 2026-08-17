# ADB GUI — Logcat 实时流 设计文档

- **日期**：2026-08-17
- **状态**：已通过设计评审，待用户审阅
- **分支**：`feat/logcat`（off master `a37bc7d`）
- **上位 spec**：`docs/superpowers/specs/2026-08-14-adb-gui-design.md`（§5.5 `streamLogcat(): Flow<LogLine>`、§11 后续阶段）

## 1. 背景与目标

v1 完成后第一个后续功能：**实时 logcat 查看器**。开发者/测试人员高频需要"看设备日志、按级别/tag/关键词过滤"，命令行 `adb logcat` 不便筛选与留存。本功能将其 GUI 化：

- 实时 tail `adb logcat`（长驻子进程，逐行流入 UI）。
- **过滤**：级别（V/D/I/W/E/F）/ tag 包含+排除 / 全文本搜索 / PID。
- **控制**：暂停/恢复、清空、导出为 .txt、复制选中行。
- 设备切换自动重起 logcat。

## 2. 关键决策

| 维度 | 决策 |
|---|---|
| 流式机制 | **复用 v1 的 `AdbStream`/`AdbProcessRunner.startStream`**（track-devices→polling 后闲置的接口）；logcat 输出干净文本行（非协议帧），直接解析 |
| 架构分层 | **方案 B**：`:core` 加 `LogcatController`（持流+环形缓冲+过滤，暴露已过滤 `StateFlow`）；UI/VM 薄渲染+转发。延续 v1 "状态在 :core、UI 只渲染" |
| 格式 | `adb logcat -v threadtime`（含 timestamp/pid/tid/level/tag/message） |
| 缓冲源 | 默认 main（`-b all`/radio/crash 切换留后续） |
| 内存封顶 | 环形 `ArrayDeque`，默认 ~10000 行（可调） |
| 暂停语义 | 暂停时丢弃新行（不积压、省内存） |
| 过滤计算 | 每行 O(1) 增量进视图；过滤条件变更 O(N) 重算（从 ring 重新筛） |
| i18n | 全部 UI 文案走 `Strings.t(...)`，zh+en（延续 v1） |

## 3. 架构与新增文件

```
:core
├─ domain/LogcatModels.kt          LogcatLine + LogcatLevel enum (V/D/I/W/E/F)
├─ adb/LogcatLineParser.kt          纯函数 parse(line): LogcatLine?
├─ adb/CommandRunner.kt            + streamLogcat(serial): AdbStream
├─ adb/AdbProcessRunner.kt          FakeAdbProcessRunner.startStream 改为可脚本化
├─ device/LogcatController.kt       [新] 持流 + 环形缓冲 + 过滤器 → lines: StateFlow<List<LogcatLine>>
└─ device/DeviceRepository.kt      + streamLogcat(serial) 委托 commands

:desktop
├─ ui/LogcatViewModel.kt           包 controller：暴露 lines/filters/status/error；selectedSerial.collect { start(it) }
└─ ui/LogcatScreen.kt              过滤栏 + 控制按钮 + LazyColumn（按级别着色、自动滚底、点选复制）
```

**分层不变量（延续 v1）**：`:core` 持状态+过滤，UI 只渲染 `controller.lines` + 转发过滤/控制。`LogcatLineParser` 纯函数独立单测。流式复用 `AdbStream` 测试缝。

## 4. 组件职责

### 4.1 `LogcatLine` / `LogcatLevel`（`domain/LogcatModels.kt`）
```kotlin
enum class LogcatLevel { V, D, I, W, E, F }
data class LogcatLine(
    val raw: String,           // 原始行（导出/复制用）
    val timestamp: String,    // "MM-DD HH:MM:SS.uuu"
    val pid: Int,              // 解析失败 = 0
    val tid: Int,              // 解析失败 = 0
    val level: LogcatLevel,    // 解析失败 = V（兜底）
    val tag: String,           // 解析失败 = ""
    val message: String,       // 解析失败 = raw
)
```
（用 `LogcatLevel` 命名，避免与 app 自身 `Logger.LogLevel` 冲突。）

### 4.2 `LogcatLineParser`（`adb/LogcatLineParser.kt`）
纯函数 `object`，`fun parse(line: String): LogcatLine?`。解析 threadtime：
```
08-17 10:23:45.123  1234  5678 I ActivityManager: Display changed
```
正则拆 `timestamp / pid / tid / level / tag / message`。非匹配行（空行 / `--- end of ...` 分隔 / 罕见格式）→ `null`，controller 的 `mapNotNull` 丢弃。

### 4.3 `CommandRunner.streamLogcat`（`adb/CommandRunner.kt`）
```kotlin
fun streamLogcat(serial: String): AdbStream {
    server.ensureStarted()
    return runner.startStream(adb(), listOf("-s", serial, "logcat", "-v", "threadtime"), scope)
}
```
返回 `AdbStream`（`lines: Flow<String>` + `kill()` + `isAlive`）。controller 负责收集 + kill。

### 4.4 `FakeAdbProcessRunner.startStream` 改为可脚本化（`adb/AdbProcessRunner.kt`）
v1 里它抛 `UnsupportedOperationException`。改为：`fun setStreamLines(lines: List<String>)` → `startStream` 返回一个假 `AdbStream`，按行 emit 后 `isAlive=false`、`kill()` no-op。供 `LogcatControllerTest` 注入脚本行（不依赖真 adb、不起 IO、确定性）。

### 4.5 `LogcatController`（`device/LogcatController.kt`）—— 核心
构造 `(commands: CommandRunner, logger: Logger, scope: CoroutineScope)`。

**状态**（`StateFlow`）：
- `lines: StateFlow<List<LogcatLine>>` —— **已过滤**视图（UI 唯一数据源）
- `filters: StateFlow<LogcatFilters>` —— `data class LogcatFilters(levelSet: Set<LogcatLevel>, tagInclude: String?, tagExclude: String?, text: String?, pid: Int?)`，默认全级别、无过滤
- `status: StateFlow<LogcatStatus>` —— `IDLE / RUNNING / PAUSED / RECONNECTING / FAILED`
- `error: StateFlow<String?>`

**私有**：`_ring: ArrayDeque<LogcatLine>`（封顶 ~10000）、`_filtered: ArrayDeque<LogcatLine>`（已过滤视图的 backing，避免 `List + line` 的 O(N²)）、当前 `AdbStream` 引用、collect job。`_lines: MutableStateFlow<List<LogcatLine>>` 在变更时 emit `_filtered.toList()` 快照。

**方法**：
- `start(serial)`：`stop()` 旧流 → 清 `_ring`+`lines`（新设备新流不混旧）→ 取 `commands.streamLogcat(serial)` → `scope.launch { stream.lines.mapNotNull{ LogcatLineParser.parse(it) }.collect { onLine(it) } }`；流结束/异常 → 指数退避重启（1s→…→30s 封顶），3 次失败置 `FAILED` 继续尝试；`status=RUNNING`。
- `stop()`：`adbStream?.kill()` + 取消 collect job；`status=IDLE`。
- `pause()`/`resume()`：切 `status`；`PAUSED` 时 `onLine` 丢新行。
- `clear()`：清 `_ring` + `_lines`（不停止流）。
- `setFilters(f)`：更新 `_filters`，从 `_ring` 重算 `_lines`。
- `export(): String`：当前 `_lines`（已过滤）逐行 `raw` 拼文本。
- `selectedLines` 复制：UI 层处理，不进 controller。

**`onLine(line)`**：若 `PAUSED` → 丢弃；否则 `_ring.addLast`，超 10000 `removeFirst`（ring 维持 ~10000）；若过当前 filter → `_filtered.addLast`（同步裁剪超 10000）+ `_lines.value = _filtered.toList()`。过滤变更 → 重建 `_filtered = _ring.filter { matches(it, filters) }`（O(N) 重算）+ emit。这样每行 O(1) 摊还（deque addLast + toList 快照是 O(N) 但常数小，受 ring 封顶约束可控）。

**日志（DEBUG）**：不逐行记；记流启停 / 重连 / `status` 变化 / export 摘要。

### 4.6 `DeviceRepository`
`suspend fun streamLogcat(serial: String): AdbStream = commands.streamLogcat(serial)`（委托；`suspend` 因 `ensureStarted` 是 suspend）。

### 4.7 `LogcatViewModel`（`ui/LogcatViewModel.kt`，薄）
- 构造 `(controller: LogcatController, selectedSerial: StateFlow<String?>, scope: CoroutineScope)`。
- 暴露 `controller.lines`/`filters`/`status`/`error`（直转）。
- `private val refreshJob = scope.launch { selectedSerial.collect { controller.start(it) } }` + `fun stop()`（测试清理，仿 v1 模式）。
- `setFilters/pause/resume/clear/export` 全转发 controller。`export()` 返回文本，**不碰文件**（Save FileDialog 在屏幕层）。

### 4.8 `LogcatScreen`（`ui/LogcatScreen.kt`）
- **过滤栏**：级别勾选（V/D/I/W/E/F）、tag 包含 TextField、tag 排除 TextField、文本搜索 TextField、PID TextField；变化即 `vm.setFilters`。
- **控制按钮**：Pause/Resume（按 `status` 切文案）、Clear、Export（→ FileDialog 存 `logcat_<stamp>.txt` + Open/Open-folder 链接，复用 v1 `FileOpen` 工具）、Copy。
- **日志列表** `LazyColumn`：每行 `[time] [pid] [level] [tag]: msg`，**按级别着色**（V/D 灰、I 黑、W 橙、E 红、F 红粗体）；**自动滚底**除非用户向上滚（监听 scroll state）；点行选中供 Copy。
- **状态/错误**：`RECONNECTING/FAILED` 顶置灰条；无设备→空状态引导；`error` 内联折叠 adb 原文。

### 4.9 接驳
- `AppShell` 侧栏 nav 加 `NavPage.LOGCAT`（Device Info/Screenshot 旁）。
- `Main.kt` 构造 `LogcatController`（root.scope）+ `LogcatViewModel`，传 AppShell；`selectedSerial != null && page==LOGCAT` 时渲染 `LogcatScreen`。
- `Strings` 加 logcat i18n key（zh+en）。

## 5. 数据流

```
adb logcat -v threadtime (长驻子进程, 干净文本行)
  └─ AdbStream.lines: Flow<String>
      └─ LogcatLineParser.parse → LogcatLine? (null 丢弃)
          └─ LogcatController.onLine
              ├─ ring (ArrayDeque, 封顶 10000, 溢出最旧)
              └─ matches(filters)? → lines StateFlow (已过滤)
UI: LogcatScreen.collectAsState(lines) → LazyColumn 渲染
控制: setFilters → 重算 lines (从 ring 重筛); pause → onLine 丢行; clear → 清 ring+lines; export → lines.raw 拼文本
设备切换: selectedSerial.collect → controller.start(new) (stop 旧流 + 清 ring + 起新流)
```

## 6. 错误处理

| 故障 | 处理 | UI |
|---|---|---|
| 流子进程死亡 | 指数退避重启（1s→…→30s），`status=RECONNECTING`；3 次失败 `FAILED` 继续尝试 | 列表顶置灰条"正在重连 logcat…" |
| adb 找不到 / server 起不来 / 设备 offline | `AdbNotFoundException`/`AdbCommandException` → controller 设 `error` | 列表区内联错误 + adb 原文折叠 |
| 切设备 | 旧流 `stop()`（kill+取消 collect），不泄漏子进程 | 旧日志清空、新流重建 |
| 暂停 | 丢弃新行（不积压） | 按钮文案 Resume |

延续 v1：错误本地化、可恢复自动恢复、不打断。

## 7. 测试策略（`:core` TDD，延续 v1）

1. **`LogcatLineParserTest`**（最高 ROI）—— 真实 threadtime fixture（多级别/tag/pid + 空行 + `--- end of` 分隔 + 罕见格式），断言字段解析 + null 兜底。
2. **`LogcatControllerTest`** —— 喂脚本化假流（`FakeAdbProcessRunner.setStreamLines`）：断言 `lines` 含解析行、ring 封顶、过滤（level/tag/text/pid）、pause 丢行、clear 清空、export 文本、设备切换重起。
3. **`CommandRunnerTest`** —— `streamLogcat` 用假 `AdbProcessRunner` 返回假 `AdbStream`，断言传参 `-s serial logcat -v threadtime` + `ensureStarted` 先调。
4. **`LogcatViewModelTest`** —— 假 controller（或真 controller + 假流）断言 `selectedSerial.collect { start }` + 转发 + `stop()` 清理。
5. UI 以 VM 单测为主；Compose 快照不强制。

**测试债务提醒**：LogcatController/VM 测试用假 `AdbStream` 注入、不起真 IO，应确定性（不撞 v1 的 `Dispatchers.IO` 竞态债）。若 VM 测试构造真 `DeviceRepository` 会撞那个 flaky 债——用假 controller 绕开；dispatcher 注入留作独立技术债项不在此扩。

## 8. 范围边界（非本期不做）

- 多 buffer 源（`-b all`/radio/crash 切换）—— 默认 main，后续加。
- `-d`（dump 一次退出）模式 / 历史日志回看 —— 本期只做实时 tail。
- 高级过滤（正则、tag 颜色自定义、保存/加载过滤预设）。
- 性能：>10k 行虚拟化优化（v1 用 LazyColumn + ring 封顶，够用；超大流量后续再优化）。

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 高频行（爆量设备）刷爆内存/UI | ring 封顶 ~10000 + 暂停丢行；LazyColumn 虚拟化；超大流量后续优化（§8） |
| 流子进程不稳定（adb server 重启/设备掉线） | 指数退避自愈 + FAILED 继续尝试（仿 v1 DeviceTracker） |
| threadtime 格式跨设备/版本差异 | Parser 单测覆盖多 fixture；非匹配行 null 兜底不崩 |
| 切设备旧流泄漏子进程 | `stop()` 强制 `kill()` + 取消 collect |
| 复用 v1 闲置 `startStream` 接口——其 `JvmAdbProcessRunner` 实现未在 v1 经真机验证（track-devices 改轮询后无调用方） | logcat 是首个真消费者；开发时真机验证流生命周期 + kill |
