# ADB GUI — Logcat 录制（自动 + 手动）设计

- **日期**：2026-09-30
- **状态**：设计已与用户确认（对话定稿），待复核后转 writing-plans
- **范围**：新增 Logcat 录制子系统 —— ①独立录制组件 `LogcatRecorder`；②手动开始/停止录制（页面按钮）；③自动后台录制（设置开关 + 常驻服务）；④录制文件管理（目录、轮转、清理）
- **依据**：用户对现有导出按钮不信任（实测确认其语义为一次性 `logcat -d` 快照，非会话完整记录）；CLAUDE.md 架构红线与技术债规范；`docs/superpowers/specs/2026-08-17-logcat-design.md`、`2026-09-16-page-driven-loading-and-logcat-publish-design.md`
- **参照既有实现**：`LogcatController`（runLoop 重连模式）、`LogcatViewModel.exportFull`（BufferedWriter 写文件惯例）、`SettingsStore`、`DeviceRepository`/`DeviceTracker`（长生命周期 collector 模式）

## 1. 背景与动机

用户需求（2026-09-30 对话）：

- 场景 A（手动）：复现 bug 时"点击开始就记录，结束就停止"，不限时长。
- 场景 B（自动）："一直在录"，出事后能导出过去的内容。
- 根本诉求：**对导出内容的完整性有信心**。

现状实测（探索结论，2026-09-30）：

| 现有功能 | 实际语义 | 完整性问题 |
|---|---|---|
| 导出按钮（`exportFull`，`LogcatViewModel.kt:59`） | 点击瞬间执行 `adb logcat -d`，导**设备 logd 环形缓冲当时快照** | 不是会话期间的内容；logd 自身滚动覆盖，早掉的行永久丢失 |
| 复制按钮（`export`） | UI 内存 ring（`ringCap` 行，**过滤后**） | 超上限即丢；过滤丢行 |
| 实时流 | 不落盘；**暂停期间新行直接丢弃**（`LogcatController.onLine:183`） | 无任何持久记录 |

**结论**：现有机制没有一条路径能保证"从 X 到 Y 时刻的完整设备日志"。本设计新增录制子系统补上这一层。

## 2. 核心决策

### D1：录制 = 独立 adb 进程，不复用页面流

`LogcatRecorder` 自己调 `commands.streamLogcat(serial)` 起一个 `adb logcat -v threadtime` 进程，逐行写文件。**不 tee 页面流**，因为页面流是"进页面才开、切走就停"（2026-09-16 页面驱动加载），tee 它会导致切页面录制中断。独立进程使：

- 手动录制跨页面存活（切到 App 管理页等继续录）；
- 后台自动录制与页面无耦合，复用同一组件。

代价：最坏情况每设备并发 3 个轻量 reader（页面流 + auto + manual），见 §7 性能。

### D2：录制原始行，不解析、不过滤

文件里写 `AdbStream.lines` 的原始字符串。`LogcatLineParser`、级别过滤都不跑（那是显示层的事）。录制文件 = 设备日志的完整事实；过滤需求留到查看时（可用任何编辑器/工具）。此决策同时是性能决策：录制路径 CPU ≈ 0。

### D3：UI ring 上限与录制无关

页面显示的 `ringCap`（默认 50000）只管显示，录制文件无行数/时长上限。现有导出/复制按钮**保持现状不动**——其定位明确为"快照"；录制文件才是完整事实。

## 3. 组件设计

### 3.1 `LogcatRecorder`（:core，新增，`core/src/main/kotlin/com/adbgui/core/device/LogcatRecorder.kt`）

一个录制单元 = 一个 adb logcat 流 → 一个文件。

```kotlin
class LogcatRecorder(
    private val commands: CommandRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,          // 调用方注入（manual 用 root.scope；测试注入测试 scope）
    private val io: CoroutineDispatcher = Dispatchers.IO,   // 技术债规范 #1：不许硬编码
    private val file: Path,
    private val rotationBytes: Long = 100 * 1024 * 1024,    // 测试注入小值
)
```

**状态**（`StateFlow<RecordState>`，含 `file`、`lineCount`、`bytesWritten`、`error`、`active`）：
- `IDLE`（构造后/stop 后）→ `RECORDING`（start 成功）→ `STOPPED`（正常 stop，文件已 flush+close）。
- 断流重连期间保持 `RECORDING`，`error` 短暂置消息后清除（内联展示用，不弹模态）。

**行为**：
- `start(serial: String)`：创建目录 + 打开 `BufferedWriter`（64 KB 缓冲）→ 起内含 runLoop 的 job。
- runLoop 复用 `LogcatController.runLoop` 模式：`commands.streamLogcat` → collect → `withContext(io) { writer.write(line) }` → 断流/异常指数退避重连（1s→…→30s 封顶，**不设失败上限**，一直重试）。重连成功后在文件里写一行标记（见 §3.5）。
- `stop()`：cancel job → `stream.kill()` → flush + close writer（在 io 上）。**stop 必须保证已写行全部落盘**。
- 定时 flush：每 1s flush 一次（崩溃最多丢尾 1 秒）；`stop()` 强制 flush。行数/字节数状态在写线程更新，`@Volatile`/StateFlow 保证可见性（技术债规范 #3）。
- 字节数超过 `rotationBytes`：flush + close 当前文件 → 原地结束（调用方决定是否续开新文件，见 §3.3）。`LogcatRecorder` 本身单文件单命，不做自我续命——保持单元简单。

**文件写入协议**：写原始行 + `\n`；不解析、不过滤（D2）。

### 3.2 `LogcatRecordingManager`（:core，新增，`core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt`）

编排层，`Main.kt` 里以 `remember{}` 构造一次、长生命周期（同 `LogcatController` 构造处 `Main.kt:76` 的惯例）。输入：

```kotlin
class LogcatRecordingManager(
    private val commands: CommandRunner,
    private val tracker: IDeviceTracker,        // 复用 DeviceRepository 的 tracker 接口
    private val settings: SettingsStore,        // 读 autoRecord 开关
    private val logger: Logger,
    private val scope: CoroutineScope,          // root.scope，长生命周期（同 DeviceTracker 约定）
    private val recordingsDir: Path,            // ConfigDir.resolve("logcat-recordings")
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,  // 测试注入
)
```

**手动录制**（一次最多一个会话）：
- `startManual(serial)`：为 serial 起 `LogcatRecorder`，文件名 `manual_<serialSafe>_<yyyyMMdd-HHmmss>.log`。
- `stopManual()`：stop recorder，状态定格为 STOPPED（含文件路径），UI 用现有 `SavedFileBanner` 展示。
- 设备下线（tracker 报 OFFLINE）：自动 stopManual 并在状态里注明"设备断开，录制已停止"，文件保留。
- 录制中用户切换选中设备：**不影响**进行中的录制（recorder 独立于页面，D1）。
- `manualState: StateFlow<ManualRecordState>`（IDLE / RECORDING(serial, file, lineCount, bytes) / STOPPED(file, lineCount, bytes, reason)）。

**自动录制**（"行车记录仪"）：
- 收集 `settings.state.map { it.logcatAutoRecord }` 与 `tracker.devices`。
- 开关开 && 设备 ONLINE → 为该设备起 auto recorder（`auto_<serialSafe>_<stamp>.log`）；设备 OFFLINE/拔出 → stop。
- `logcatAutoRecord` 关→开：对当前在线设备立即补启；开→关：全部停止。
- 一个设备一个 auto recorder；auto recorder 因 rotation 结束时，管理器自动续开新文件（新 stamp），对用户无感。
- `autoStates: StateFlow<Map<String, RecordState>>`（serial → 状态，供设置页/页面显示"后台录制中 · N 台设备"）。

**serial 文件名净化**：无线设备 serial 含 `:`（如 `192.168.1.5:5555`），Windows 文件名非法。统一 `serialSafe = serial.replace(":", "_").replace(Regex("[\\\\/*?\"<>|]"), "_")`。

**启动清理**：manager 构造时（io 上）扫描 `recordingsDir`，删除 `auto_*.log` 中 `lastModified` 早于 7 天的文件；`manual_*.log` **永不自动删**。失败仅 WARN 日志，不阻塞启动。

### 3.3 设置

`Settings` 新增字段（`SettingsStore.kt`）：

```kotlin
/** 设备上线时自动后台录制其 logcat 到 logcat-recordings/（"行车记录仪"模式）。
 *  应用运行期间有效；auto 文件保留 7 天、单文件 100 MB 轮转。 */
val logcatAutoRecord: Boolean = true,
```

设置页（`SettingsScreen`）新增开关"自动录制设备日志"，带说明文案（含"应用需保持运行"）。常量（7 天 / 100 MB）为编译期常量，不做设置项（YAGNI）。

### 3.4 UI（Logcat 页）

`LogcatScreen` 工具栏（pause/copy/export 按钮排，`LogcatScreen.kt:108-140`）新增：

- **录制按钮**：未录制时红色圆点 ●"开始录制"；录制中变红色方块 ■"停止录制"，旁边内联显示 `录制中 · 12,345 行 · 3.2 MB`（1s 级刷新足够）。
- 停止后沿用现有 `SavedFileBanner`（打开文件 / 打开文件夹）。
- 无设备选中/离线：按钮禁用；录制中设备下线：状态转 STOPPED + banner 提示"设备断开，录制已停止"。
- 后台自动录制状态：工具栏右侧一个低调的 chip（"后台录制中"），点击打开 `logcat-recordings/` 文件夹；auto 关闭时不显示。

`LogcatViewModel` 增加对 `LogcatRecordingManager` 的转发（构造注入，模式同现有 `controller` 注入）：`manualRecordState`、`startRecording()`、`stopRecording()`、`autoStates`、`openRecordingsFolder()`。数据与回调仍不越层碰 adb（红线 #2：VM → manager → CommandRunner，同 VM → LogcatController → CommandRunner 的既有惯例）。

错误内联：录制失败（adb 死、磁盘满）在录制状态条内联显示 + 可重试；后台 auto 失败只记 DEBUG/WARN 日志并退避重试，不出现在页面上（不弹模态，符合错误处理约定）。

### 3.5 重连标记行

logcat 自身会输出 `--------- beginning of /dev/log/main` 这类分隔行，据此惯例，重连成功时写：

```
--------- adbgui reconnect 2026-09-30 12:34:56.789 serial=XXXX
```

与设备日志天然可区分（`adbgui` 前缀），grep/解析工具不会误读为日志行。

## 4. 数据流总览

```
adb logcat 进程（每 recorder 独立）
   │ AdbStream.lines (Flow<String> 原始行)
   ▼
LogcatRecorder ──(withContext(io), BufferedWriter, 1s flush)──▶ logcat-recordings/*.log
   ▲ start/stop/rotation
   │
LogcatRecordingManager ──┬─ 手动: LogcatViewModel(转发) ← LogcatScreen 工具栏按钮
   │                     └─ 自动: settings.logcatAutoRecord + tracker.devices
   │
CommandRunner / IDeviceTracker / SettingsStore（既有，不改动语义）
```

## 5. 边界与不做的事

- **应用关闭即停止录制**：无法绕过（进程都没了），spec 如实声明；设置开关文案注明。
- **TCL 等 logd 被出厂禁用的设备**：录制器拿到空流，录出空文件。修复沿用页面现有"fix logd"流程；录制器**不**自动调 `fixLogcatDisabled`（避免每次重连都动系统属性，副作用失控）。已知局限，接受。
- **manual 录制与 auto 录制并存**：同一设备两个进程、两个文件并存，可接受（§7）；不做"manual 时挂起 auto"的优化。
- **页面流与录制流合并**（省一个进程）：需要重构 LogcatController，明确留作后续优化，本期不做。
- **录制内容的时间段截取**（从 auto 文件剪某段另存）：本期不做，manual 录制即时间段语义。
- 现有导出（`logcat -d` 快照）/复制按钮行为不变。

## 6. 错误处理

- 录制器异常：捕获进 `error` 状态 + WARN 日志，退避重试（同 runLoop 模式），不中断应用。
- 磁盘满：写失败 → recorder 置 error 并停止该 recorder（不能无限重试写盘），UI 内联提示。
- `stop()` 时 writer close 异常：WARN 日志 + 状态带 error，文件尽力保留已写内容。
- 文件名冲突（同秒同 serial 两次录制）：stamp 精确到毫秒即可避免；仍冲突则递增后缀 `_1`。

## 7. 性能设计（用户明确要求）

**桌面端**
- 不解析、不过滤（D2）：录制路径无每行 CPU 开销。
- 批量写盘：64 KB BufferedWriter + 每 1s 定时 flush；空闲设备日志量 KB/s 级，SSD 无感。
- 内存零增长：不在内存累积任何行（写完即弃）；录 24h 内存占用不变。UI ring 上限照旧。

**设备端**
- 每个 `adb logcat` 是 logd 轻量 reader，logd 原生支持多读者。最坏并发 = 每设备 3 reader（页面流 + auto + manual），均为轻量进程，短暂共存可接受。
- 真实开销主要是 USB 持续传输日志文本——这正是"一直在录"的固有成本，开关可随时关闭回到零新增。

**磁盘**
- auto 文件 7 天过期 + 100 MB 轮转封顶占用上限；manual 文件由用户自管。
- 后续优化预留：页面显示订阅录制流合并进程（§5，本期不做）。

## 8. 测试计划（:core TDD，先写失败测试）

`LogcatRecorderTest`（`FakeAdbProcessRunner.setStreamLines`/`emitStreamLine`、注入 `io = Dispatchers.Unconfined`、`@TempDir`）：
1. 流式行完整落盘（顺序、无行数上限——注入超 ringCap 的行数）。
2. `stop()` 后文件立即可读且含全部已发行。
3. 流结束（EOF/异常）→ 重连 → 新行继续追加 + 重连标记行存在。
4. rotation：注入小 `rotationBytes`，超限后 recorder 结束、文件 ≤ 阈值附近。
5. 写失败（目标路径只读/指向目录）→ error 状态、不再无限重试。

`LogcatRecordingManagerTest`：
6. 设备 online + 开关开 → auto 文件出现且在写；offline → recorder 停止。
7. 开关运行时切换（关→开补启；开→关全停）。
8. `startManual`/`stopManual` 状态机：IDLE→RECORDING→STOPPED，文件名含净化后的 serial。
9. 设备下线时手动录制自动停止，reason 正确。
10. 启动清理：预置 8 天前的 `auto_x.log` 与 `manual_y.log` → auto 被删、manual 保留（注入 clock）。
11. rotation 后 manager 自动续开新 auto 文件。

`LogcatViewModelTest`：手动录制状态机转发、无设备时 startRecording no-op 错误提示。

`SettingsStoreTest`：`logcatAutoRecord` 序列化默认值。

UI（LogcatScreen）：按现有惯例以 ViewModel 单测为主，Compose 层不强制快照测试。

## 9. 实现顺序（供 writing-plans 展开）

1. `LogcatRecorder` + 测试（§8.1-5）。
2. `Settings.logcatAutoRecord` + store 测试。
3. `LogcatRecordingManager`（手动部分）+ 测试（§8.8-9）。
4. `LogcatRecordingManager`（自动部分 + 清理 + rotation 续开）+ 测试（§8.6-7、10-11）。
5. `LogcatViewModel` 扩展 + 测试；`Main.kt` 接线。
6. `LogcatScreen` UI（录制按钮/状态条/banner/后台 chip）+ 设置页开关。
7. 真机验证（用户执行）：录制完整性抽查、切页面录制存活、设备拔出行为、auto 7 天清理逻辑用注入 clock 已测，真机只验首尾。
