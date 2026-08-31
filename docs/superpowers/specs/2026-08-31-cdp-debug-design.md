# CDP Debug（WebView 调试）设计

> 状态：草案（2026-08-31）。本 spec 由 brainstorming 流程产出，等用户复核后转 writing-plans 出实施计划。
> 前置依赖：端口转发页（已合并 `master`，`071e5fb`/`696aad2`）—— CDP 的一键模式复用其 `adb forward` 能力做预设。
> 参考实现：`D:\st_tools\cdp-debug`（外部 node bridge + 静态 HTML 页，未跑通，仅作功能参照）。

## 1. 目标与范围

把 cdp-debug 那套 WebView 调试能力**原生内嵌**进 ADB GUI：在 App 内选一台设备、点一下就能调起该设备上某个 WebView 的 Chrome DevTools Protocol 调试——看 console、看 network 请求、跑 JS eval、reload——不再依赖外部 node bridge + 浏览器 HTML 页。

**v1 范围（本 spec 覆盖）**
- Console：`Runtime.consoleAPICalled` / `Runtime.exceptionThrown` / `Log.entryAdded` → 可选行 + 过滤 + 暂停/清屏 + 导出（复用 `LogcatScreen` 的交互范式）。
- Network：`Network.requestWillBeSent` / `responseReceived` / `loadingFinished` / `loadingFailed` → 请求表 + `Network.getResponseBody` 响应体弹窗。
- Eval：`Runtime.evaluate`，含子帧遍历（`window.frames[i]` eval，照 cdp-ui.html 的 `frameExpr`）。
- `Page.reload`。

**明确不做（v1 之外）**
- 截图画廊 / 自动截图（App 已有独立 `ScreenshotWindow`，不重复）。
- DOM tree / Sources / Timeline / Performance 等其它 DevTools 面板。
- `adb pair`、端口转发页的通用管理（端口转发已独立成页）。

## 2. 非目标 / 约束

- 不引入浏览器引擎（JCEF / JavaFX WebView）。UI 全部原生 Compose，与 App 其余部分一致（主题、`SelectableText`、`InlineMessageBanner`）。
- `:core` 不新增 websocket 依赖。ktor 仅在 `:desktop`。
- fixture 不手构 adb 输出（技术债规则 #4）；CDP 帧虽是稳定协议 JSON，仍**真录**真设备会话（见 §8）。
- 不弹模态错误。命令失败/连接失败一律内联 + 可操作指引。

## 3. 架构与分层

完全沿用现有 seam 模式（照 `AdbProcessRunner` 开一条新 I/O 缝；`AdbStream` 是单向行文本，撑不住 CDP 的双向 JSON over ws）。

```
:core  (纯 Kotlin，零 UI，零 ktor)
├─ domain/CdpModels.kt            CdpConsoleEntry / CdpNetworkRequest / CdpTarget / CdpConnectionState
├─ adb/CdpTransport.kt            接口：connect(url) / send(json) / incoming: Flow<String> / state / close()
├─ adb/CdpEventParser.kt          纯函数 parseEvent(method, params): CdpEvent?  （TDD + 真 CDP 帧fixture）
└─ device/CdpController.kt         仿 LogcatController：own transport job + 重连环 + 事件→StateFlow

:desktop/platform
└─ KtorCdpTransport.kt            ktor-client-cio + ktor-client-websockets 的 actual 实现

:desktop/ui
├─ CdpDebugViewModel.kt           thin：只读 CdpController 的 StateFlow + 转发 evaluate/reload/getResponseBody/stop；
│                                 一键/手动由 controller 管，VM 不碰 forward（照 LogcatViewModel→LogcatController 模式）
└─ CdpDebugScreen.kt             console 列表 + network 表 + eval 框 + target/frame 选择 + 连接状态
```

**依赖**：`:desktop/build.gradle.kts` 加 `ktor-client-cio` + `ktor-client-websockets`（版本进 `gradle/libs.versions.toml`）。`:core` 依赖不变（仍只有 coroutines + serialization-json）。

## 4. 组件细节

### 4.1 `CdpTransport`（`:core` 接口）

```kotlin
interface CdpTransport {
    suspend fun connect(url: String)                 // ws 握手；失败抛 CdpConnectionException
    suspend fun send(json: String)                   // 发一帧 CDP JSON（请求）
    val incoming: Flow<String>                        // 收到的 JSON 帧（事件 + 响应）
    val state: StateFlow<CdpConnectionState>         // DISCONNECTED / CONNECTING / CONNECTED / FAILED
    fun close()
}
```

`KtorCdpTransport`（`:desktop/platform`）包一个 ktor `HttpClient`（CIO 引擎）+ `webSocket(...)`：
- `incoming` 接到 session 的 `incoming.receiveAsFlow().map { it.data.decodeToString() }`；
- `send(json)` 转 `session.outgoing.send(Frame.Text(json))`；
- `state` 在 onopen→CONNECTED、onclose/onerror→FAILED 翻转，重连时 →CONNECTING。
- 跨线程可变状态（session 句柄）`@Volatile` 或 `Mutex`（技术债规则 #3）。

### 4.2 `CdpEventParser`（`:core` 纯函数）

```kotlin
object CdpEventParser {
    fun parseEvent(method: String, params: JsonObject): CdpEvent?
}
```

sealed `CdpEvent`：
- `ConsoleAdd(level: CdpLevel, text: String)` ← `Runtime.consoleAPICalled`（args 拼 `value`/`description`/`unserializableValue`）/ `Runtime.exceptionThrown`（`exceptionDetails.text` + `exception.description`）/ `Log.entryAdded`（`entry.level` + `entry.text`）。
- `NetRequest(id, method, url, state=SENT)` ← `Network.requestWillBeSent`。
- `NetResponse(id, status, mime)` ← `Network.responseReceived`。
- `NetDone(id, ok: Boolean, err: String?)` ← `Network.loadingFinished` / `loadingFailed`。
- 未知 method → null（不崩；`CdpController` DEBUG 级记 `unknown CDP method: <method>`，不静默吞）。

### 4.3 `CdpController`（`:core`，仿 `LogcatController`）

构造注入 `CdpTransport` + `CommandRunner`（探 webview socket + forward + removeForward，core→core，照 `LogcatController` 模式）+ `Logger` + `CoroutineScope`。**不注入 `DeviceRepository`**——CDP 自管它那条 forward，知道自己的 serial，直接走 `CommandRunner` 即可。

暴露：
- `consoleEntries: StateFlow<List<CdpConsoleEntry>>`（环形缓冲 10000 条，`Mutex` 串行写）。
- `networkRequests: StateFlow<List<CdpNetworkRequest>>`（按 `requestId` 合并的 Map 派生）。
- `targets: StateFlow<List<CdpTarget>>`（page target 列表）。
- `state: StateFlow<CdpConnectionState>`、`error: StateFlow<String?>`。

方法：
- `start(serial)` —— 一键自动流（见 §5）。
- `connectManual(port: Int)` —— 手动连指定端口（见 §5）。
- `stop()` —— 关 ws +（一键模式下）移除自建的 forward。
- `evaluate(expr: String, frame: String?): CdpEvalResult` —— suspend，发 `Runtime.evaluate`，等 `id` 响应。
- `reload()` —— 发 `Page.reload`。
- `getResponseBody(requestId: String): String?` —— suspend，发 `Network.getResponseBody`。

内部：`pending: Map<Int, Completer<JsonObject>>` 路由响应（照 cdp-ui.html 的 `cdpSend`/`pending`）；`runLoop` 收 `incoming` 帧，有 `id` → 完成 pending，有 `method` → `CdpEventParser.parseEvent` → 更新 StateFlow。重连环：指数退避 1s→30s，3 次失败降级"正在重连"灰置。

### 4.4 `CdpDebugViewModel`（`:desktop`）

thin：只读 `CdpController` 的 StateFlow + 转发 `evaluate/reload/getResponseBody/stop`；一键/手动模式切换（调 `controller.start(serial)` / `connectManual(port)`）；选 target（`CdpController` 默认取第一个 page，VM 提供 target 下拉切换 = 重连 page ws）。错误内联（`InlineMessageBanner`）。不注入 `DeviceRepository`，不碰 forward。

### 4.5 `CdpDebugScreen`（`:desktop`）

布局（仿 `SystemInfoScreen` 的左右分栏或 `cdp-ui.html` 的双栏）：
- 顶栏：连接状态点 + `targets` 下拉 + 一键"调试 WebView"按钮 / 手动端口输入框 + Reload。
- 左：Console 列表（`SelectableText`，level 着色，过滤 + 暂停 + 清屏 + 导出，仿 `LogcatScreen`）。
- 右：Network 表（method/url/status/mime/状态，点行 → `getResponseBody` 弹窗）。
- 底：Eval 输入框 + frame 下拉 + Run + 结果区（`SelectableText` 等宽）。

## 5. 连接生命周期

**一键 `start(serial)`**（默认）
1. `CommandRunner.webviewSocket(serial): String?`（新增：`adb shell cat /proc/net/unix` → 正则 `webview_devtools_remote_\d+`，复用 `runShellCmd`）。无 → 抛 `CdpConnectionException("无 webview socket — 目标应用需在前台运行且含 WebView")`。
2. `commands.forward(serial, ForwardSpec(TCP,"9222"), ForwardSpec(LOCALABSTRACT, socket))`。
3. `transport.connect("ws://localhost:9222/devtools/browser")` → 发 `Target.getTargets` → 取 page targets。
4. 选第一个 page target → `transport.connect("ws://localhost:9222/devtools/page/<id>")` → 发 `Runtime.enable`/`Page.enable`/`Network.enable`/`Log.enable`。
5. 进 `runLoop`。

`stop()`：关 page ws + browser ws；一键模式下 `commands.removeForward(serial, ForwardSpec(TCP,"9222"))`（清理自己建的 forward）；手动模式不动 forward。

**手动 `connectManual(port)`**
跳过 1-2，直接 `transport.connect("ws://localhost:<port>/devtools/browser")` → 3-5。UI 给端口输入框，给"已在外部 forward 好"的用户用。

**重连**：仅重连 page ws（forward 还在，不重 forward）。退避 1s→30s，3 次失败 → `state=FAILED` + 内联"正在重连"，不弹模态。

## 6. 数据流

```
ws 帧 → CdpTransport.incoming → CdpController.runLoop:
  ├─ 有 id 且 pending 命中 → 解析响应，完成 pending 的 evaluate/getResponseBody
  └─ 有 method → CdpEventParser.parseEvent → CdpEvent
       ├─ Console* → consoleEntries 环形缓冲（Mutex 串行）
       └─ Net* → 按 requestId 合并进 networkRequests
VM 读 StateFlow；evaluate/reload/getResponseBody 转发到 controller。
```

## 7. 错误处理（沿用项目约定）

- **ws 断线**：指数退避重连（1s→…→30s 封顶），3 次失败降级"正在重连"灰置，不弹模态。
- **无 webview socket**：`CdpConnectionException("无 webview socket — 目标应用需在前台运行且含 WebView")`，给可操作指引（照 `AdbNotFoundException`）。UI 内联显红 + 指引。
- **forward 失败**：复用端口转发页 inline 格式 `("${e.message}\n--- adb stderr ---\n${e.stderr}")`，`InlineMessageBanner` 红。
- **forward 建好但 ws 连不上**（`tcp:9222` 没监听）：`"forward 已建但 ws 连不上 — WebView 可能已退出或切了页面"`，建议重试。
- **eval 的 JS 异常**：`Runtime.evaluate` 返回 `exceptionDetails` → 在 eval 结果区显 JS 异常文本（非模态）。
- **未知 CDP method**：`CdpEventParser` 返回 null 跳过，DEBUG 级记 `unknown CDP method: <method>`（不静默吞）。

## 8. 测试策略

`:core` 全 TDD：
- **`CdpEventParser`**：纯函数单测，fixture = **真录 CDP 帧**（§9）。覆盖各事件类型 + 未知 method→null + 坏 JSON→不崩。
- **`CdpController`**：注入 `FakeCdpTransport`（回放录制帧到 `incoming` + 记录 `send` 调用）+ `FakeAdbProcessRunner`/`CommandRunner`。断言：事件→StateFlow、`evaluate` 请求/响应 `id` 配对（pending）、断线重连、一键模式 `start` 调 `commands.forward`、`stop` 调 `commands.removeForward`、手动模式不动 forward。
- **`CommandRunner.webviewSocket`**：fixture = 真录 `adb shell cat /proc/net/unix` 输出（规则 #4，从 VIDAA TV 录），解析 `webview_devtools_remote_<pid>`。

`:desktop` VM 单测（状态机）：`FakeCdpController`，断言 connected/connecting/failed、console 增长、eval 结果、error 内联。Compose 快照不强制。

`:desktop` actual `KtorCdpTransport` 不做单测（真实 ws，靠手动验证 + `:core` 的 `FakeCdpTransport` 覆盖逻辑）。

## 9. 真 CDP 帧 fixture 录制（用户配合）

一个 ~30 行的 ws 抓包小脚本（Kotlin 或 node），连到 `ws://localhost:9222/devtools/page/<id>`，把每条入站帧按 NDJSON（一行一帧）追加到文件。

流程：
1. 用户连上 VIDAA TV，让目标 WebView 应用跑到前台（出现 `webview_devtools_remote_*` socket）。
2. 用端口转发页 forward `tcp:9222`（或给一条命令）。
3. 跑抓包脚本，用户在应用里操作几下（刷页、`console.log`、发网络请求）。
4. 停脚本 → 产出 `core/src/test/resources/fixtures/cdp/real_session.ndjson`，带 provenance 头（设备型号 + Android 版本 + WebView 应用 + 录制日期 + 抓包命令）。
5. 按事件类型从大文件抽代表性帧进各 `cdp_console_*.ndjson` / `cdp_network_*.ndjson` 小 fixture（或整文件做回归）。

`CdpEventParser` 测真设备出来的帧变体，不是手构——符合规则 #4 精神。

## 10. 红线核对（CLAUDE.md）

- #1 `:core` 不碰 Compose/ktor：`CdpTransport` 接口、`CdpController`/`CdpEventParser` 纯 Kotlin；ktor 只在 `:desktop/platform`。✅
- #2 UI 只读 VM StateFlow、只回调 VM：VM（`:desktop`）只注入 `CdpController`（`:core`），不碰 `CommandRunner`/ws；`CdpController`（`:core`）碰 `CommandRunner` 做 forward/webviewSocket（core→core，照 `LogcatController`）。✅
- #3 `:core` 不起真 adb：forward 走现有 `DeviceRepository.forward`（`AdbProcessRunner`）；ws 连接是 localhost ws，`CdpTransport` 接口在 `:core`、ktor actual 在 `:desktop/platform`；`:core` 测试用 `FakeCdpTransport`。✅（与 `AdbProcessRunner` 同构）
- #4 平台差异藏接口背后：`CdpTransport` 接口在 `:core`，ktor actual 在 `:desktop/platform`。✅
- #5 解析与执行分离：`CdpEventParser` 纯函数；`CdpController` 调它。✅

技术债规则：
- #1 Dispatcher 注入：`CdpController` 的 I/O（ws + adb）走注入 scope，不硬编码 `Dispatchers.IO`；测试传 `Unconfined`。
- #2 无死代码：不 加"以后可能用"的 CDP 域占位。
- #3 跨线程可变状态：`CdpController` 的 transport 句柄 + pending map `@Volatile` 或 `Mutex`；环形缓冲 `Mutex` 串行（仿 `LogcatController`）。
- #4 fixture 真录：CDP 帧 + `cat /proc/net/unix` 都真录。

## 11. 实现顺序提示（给 writing-plans）

建议任务切分（供 writing-plans 参考，非最终）：
1. domain models + `CdpEventParser` + 真 CDP 帧 fixture（`:core` TDD）。
2. `CdpTransport` 接口 + `FakeCdpTransport`（`:core` 测试桩）。
3. `CdpController`（`:core`，仿 `LogcatController`，含 `FakeCdpTransport` 单测）。
4. `CommandRunner.webviewSocket` + fixture（`:core`）。
5. ktor 依赖 + `KtorCdpTransport`（`:desktop/platform`）。
6. `CdpDebugViewModel` + VM 单测（`:desktop`）。
7. `CdpDebugScreen` + 接进 `AppShell`（`:desktop`）。
8. 真机验证 + 真 fixture 录制（用户配合，§9）。

工作量粗估 ~3 天（依赖端口转发已成）。
