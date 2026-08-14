# ADB GUI 工具 — 设计文档

- **日期**：2026-08-14
- **状态**：已通过设计评审，待用户审阅
- **作者**：brainstorming 协作产出

## 1. 背景与目标

开发者与测试人员日常频繁使用 `adb`，但命令行门槛高、常用命令分散、重复连接设备繁琐。本工具将这些操作 GUI 化，提升效率：

- 快速连接新设备（USB / 无线）与**重连连接过的设备**
- 常用 adb 命令 GUI 化（应用管理、文件/媒体、Shell/Logcat、设备信息/投屏、调试/系统操作）
- 多设备并存管理

## 2. 关键决策

| 维度 | 决策 |
|---|---|
| 平台范围 | **Windows 优先**，架构预留 Mac/Linux 扩展 |
| 技术栈 | **Compose Multiplatform（Kotlin/KMP）**，桌面 target（已稳定多年） |
| adb 依赖 | **可配置**：内置 adb 为默认，设置中可指向自定义路径；找不到时退回系统 PATH |
| 分发 | 同时出 **MSI 安装包**（jpackage 捆绑 JRE）与 **免安装便携版** |
| 连接模型 | USB + 无线（`adb connect`），**多设备并存** |
| 设备历史 | 持久化：`serial` / `alias` / `type` / `wirelessIp` / `wirelessPort` / `lastConnectedAt` |
| 后端 ↔ adb 架构 | **方案 B**：`track-devices` 事件流做设备发现 + `adb -s <serial>` 子进程做命令执行 |

## 3. v1 范围（分阶段）

采用分阶段交付，所有阶段共用同一设备/连接核心：

- **v1（本设计覆盖）**：连接管理核心（USB/无线、多设备、历史重连）+ 应用管理（安装/卸载/清数据/列包）+ 设备信息面板 + 截图
- **后续阶段**：Shell 终端页 / Logcat 实时流 / 文件 push-pull / 投屏（scrcpy 集成）/ 调试与系统操作（端口转发、`adb pair`、reboot/recovery、root/remount、monkey 压测）

> v1 刻意收敛到"核心 + 最高频能力"，把高风险的流式子系统（Logcat、Shell、scrcpy）留到后续阶段，降低单次实现风险。

## 4. 架构与模块分层

单一可执行桌面应用，KMP 多模块：

```
adb-gui/
├── build.gradle.kts            (根)
├── :core                       纯 Kotlin/JVM，平台无关
│     ├─ domain/                 Device, DeviceType, DeviceStatus 等不可变模型
│     ├─ adb/                    AdbLocator, AdbServerController,
│     │                           DeviceTracker, CommandRunner
│     ├─ device/                 DeviceRepository, DeviceHistoryStore
│     └─ settings/               SettingsStore
└── :desktop                    Compose Multiplatform 桌面应用(可运行)
      ├─ ui/                      各功能页 Compose 界面 + ViewModel
      ├─ platform/                expect/actual 实现：内置 adb 资源路径、配置目录、文件对话框
      └─ main/                    应用入口、依赖注入、生命周期
```

### 4.1 分层原则

- `:core` **不依赖 Compose、不依赖任何 UI**：所有 adb 交互、设备状态、持久化、解析都在此层，纯 Kotlin 可单测。
- `:desktop` 只负责把 `:core` 的 `StateFlow` 渲染成 UI 并把用户操作回调下去。
- 收益：(1) `:core` 可被任意换 UI 复用；(2) `:core` 单测不启 UI、不起真 adb，用假实现注入录制好的 adb 输出即可。

### 4.2 跨平台扩展策略

- `:core` 是 JVM，Mac/Linux 上 `adb` 子进程调用（`ProcessBuilder`）天然可移植。
- 唯一平台差异：**内置 adb 二进制路径**、**默认配置目录**、**打包格式**——藏在 `:desktop/platform` 的 `expect/actual` 接口背后。
- 将来加 Mac/Linux：新增对应 actual 实现 + 对应平台二进制 + 打包脚本，`:core` 一行不改。

## 5. 核心组件与职责

`:core` 内每个组件职责单一、可独立测试：

### 5.1 `AdbLocator`
- 解析顺序：`SettingsStore.adbPath` 覆盖 → 内置 `adb`（平台资源）→ `PATH` 上的系统 `adb`
- 返回 `AdbBinary(path, source)`；找不到抛 `AdbNotFoundException`（带可操作信息：去设置指向 adb）

### 5.2 `AdbServerController`
- `ensureStarted()`：执行 `adb start-server`，确保 server 在（幂等、轻量）
- 监听 server 死亡信号，必要时重启；提供 `restartServer()`
- 所有命令执行前先 `ensureStarted()`

### 5.3 `DeviceTracker`（设备发现的实时源）
- 维持一个常驻 `adb track-devices` 子进程，逐行解析 `List-of-devices-attached` + `<serial> <state>` 事件
- 暴露 `devices: Flow<List<DeviceSnapshot>>`（去抖合并）
- 自愈：进程退出 / adb server 重启 → 指数退避重启，期间发 `Reconnecting` 状态事件
- v1 不解析 `track-devices -l` 扩展字段；设备详情用 `adb -s <serial> getprop` 按需懒加载

### 5.4 `DeviceHistoryStore`（持久化"连过的设备"）
- JSON 文件存用户配置目录，字段：`serial`, `alias?`, `type(USB/wireless)`, `wirelessIp?`, `wirelessPort?`, `lastConnectedAt`
- 点击无线历史设备 → 用存的 IP:Port 调 `adb connect`

### 5.5 `CommandRunner`（所有命令执行）
- 方法：`connect/disconnect`、`install/uninstall/clearData/listPackages`、`deviceProps(serial)`、`screenshot`、`pull/push`
- 每个方法：起 `adb -s <serial> <args>` 子进程、捕获 stdout/stderr、超时控制、调用对应 **Parser** 返回强类型结果或抛带 adb 原文的 `AdbCommandException`
- logcat 等流式命令（后续阶段）：`streamLogcat(): Flow<LogLine>`

### 5.6 `DeviceRepository`（UI 唯一数据源）
- 合并 `DeviceTracker` 实时流 + `DeviceHistoryStore` 历史记录 → 暴露 `StateFlow<List<DeviceView>>`
- `DeviceView` = 实时状态(live/offline) + 历史元数据(别名/IP/Port/lastSeen) 的合并视图
- `connectWireless(ip,port)`：委托 `CommandRunner` 成功后更新历史（lastSeen + 存 IP:Port）
- 支持别名编辑、删除历史项

### 5.7 解析与模型分离
- 每个 adb 命令的文本输出有独立 `XxxParser`（纯函数 `parse(input): Result`），`CommandRunner` 调用它们
- Parser 是最易测的部分

### 5.8 UI 侧（`:desktop`）
- 侧边栏设备列表 + 主区功能页（应用管理 / 设备信息 / 截图）
- 各页一个 `ViewModel` 订阅 `:core` 的 `StateFlow`

## 6. 关键数据流

### 6.1 场景 A：无线设备连接（含历史重连）

```
用户点击历史无线设备 / 输入 IP:Port
  └─ ConnectViewModel.connect(ip, port)
      └─ DeviceRepository.connectWireless(ip, port)
          ├─ CommandRunner.connect(ip, port)        adb connect ip:port → Parser 判成功
          │    └─ 失败 → 抛 AdbCommandException(adb原文) → UI 显示错误
          └─ 成功 → DeviceHistoryStore.upsert(serial, type=wireless, ip, port, lastSeen=now)
DeviceTracker 的 track-devices 流随后自动捕获到该设备 → DeviceRepository 合并 → StateFlow 更新
  └─ DeviceListViewModel 观察 StateFlow → 侧边栏实时出现"已连接"设备（无需手动刷新）
```

- 连接成功后**不手动刷新设备列表**——`track-devices` 流会推上来，UI 被动响应。
- 历史持久化与实时流解耦，互不阻塞。

### 6.2 场景 B：应用管理（列包 + 安装）

```
用户在侧边栏选中设备 serial=X，进入应用管理页
  └─ AppManagerViewModel 用 selectedSerial 调 DeviceRepository.listPackages(serial)
      └─ CommandRunner.listPackages() → adb -s X pm list packages -3 → PackageListParser
          → List<PackageInfo(name, source="third-party")> → StateFlow → UI 列表
用户拖入 apk 安装
  └─ install(apkPath, reinstall=true) → adb -s X install -r <apk> → InstallParser
      ├─ 成功 → 刷新包列表 + UI toast "已安装"
      └─ Failure [INSTALL_FAILED_OLDER_SDK / 签名冲突 / ...] → 解析码 → 可读提示
```

### 6.3 不变量

- UI 永远只读 `DeviceRepository` 的 `StateFlow`，永远只回调 `Repository` 的方法——**不直接碰 adb、不直接碰 `CommandRunner`**。
- 设备状态变化由 `DeviceTracker` 流单向驱动，无需"刷新"按钮。

### 6.4 并发与取消

- `CommandRunner` 用 `CoroutineScope`，每个命令可取消（用户切走 / 换设备时取消未完成命令）。
- `track-devices` 用独立长生命周期 scope，不随功能页销毁。

## 7. 错误处理

分档处理，都带可操作信息，不让用户对着 adb 原文发呆：

| 故障 | 处理 | UI 表现 |
|---|---|---|
| adb 找不到 / server 起不来 | `AdbNotFoundException` | 顶部横幅："未找到 adb，[打开设置指向 adb 路径]" |
| `track-devices` 流中断 | 指数退避(1s→2s→…→30s 封顶)自愈重启 | 设备列表置灰 + "正在重连 adb…"，恢复自动转正常 |
| 单条命令失败 | `AdbCommandException(code, rawStderr)` | 功能页内联错误 + adb 原文（折叠展开），不弹模态 |
| 无线连接超时/拒绝 | 解析 adb 文案("cannot connect"/"device offline") | 输入框下提示原因 |
| 无设备选中就操作 | `NoDeviceSelectedException`（前置守卫） | 禁用相关按钮 + 空状态引导 |

**原则**：错误本地化——能就地显示的绝不弹模态打断；可恢复的（adb 重连）自动恢复，仅短暂提示。

## 8. 配置与持久化

- `SettingsStore`：JSON 文件存用户配置目录（Windows: `%APPDATA%/AdbGui/settings.json`），含 `adbPathOverride?`、`theme`、`windowBounds`
- `DeviceHistoryStore`：同目录 `devices.json`
- 启动时序：`AdbServerController.ensureStarted()` → `DeviceTracker.start()` → 加载历史 → 合并首帧状态

## 9. 日志

排查 adb 包装类工具的问题，日志几乎必备（adb 原始输出、track-devices 事件、server 重启、命令成败都是事后排障关键）。

- **记什么**：adb 命令调用（含 `-s serial` 与参数、退出码、stdout/stderr 摘要）、`track-devices` 事件、`AdbServerController` 生命周期、自愈重连、未捕获异常、应用启动/退出。
- **分级**：DEBUG（adb 原始 I/O）／INFO（生命周期）／WARN（重连、降级轮询）／ERROR（失败）。默认 INFO；设置中可切 DEBUG（用户排障时按指引打开）。
- **位置**：滚动文件存用户配置目录（Windows: `%APPDATA%/AdbGui/logs/`），按大小轮转（如 5 文件 × 2MB）；开发期额外输出控制台。
- **抽象**：`:core` 放轻量 `Logger` 接口，`:desktop` 提供文件轮转 actual 实现——保持 `:core` 可测（测试用假/内存 Logger）。
- **可用性**：设置页"打开日志文件夹"与"导出日志（打包最近 N 个文件）"按钮，便于用户回传日志。
- **敏感性**：不记敏感数据；apk 内容/路径仅在 DEBUG 以外不落盘，DEBUG 级记录路径不含二进制内容。

## 10. 测试策略

`:core` 可测性是分层的核心回报。覆盖优先级：**Parser > Repository > Tracker > CommandRunner > UI**。

1. **Parser 单测**（最高 ROI）：用真实录制的 adb 输出做 fixture，逐 Parser 断言。覆盖 `devices -l`、`pm list packages`、`install` 成败各码、`getprop`、`adb connect` 各文案。
2. **`CommandRunner` 单测**：`FakeAdbProcess`（接口注入进程执行结果）——模拟 stdout/stderr/退出码，验证正确调 Parser、处理超时、构造异常。
3. **`DeviceTracker` 单测**：喂假的 track-devices 字节流（设备增/删/状态切换），断言 Flow 事件序列与去抖合并。
4. **`DeviceRepository` 单测**：假 `DeviceTracker` + 内存 `DeviceHistoryStore`，验证"实时 + 历史"合并、connect 成功后历史更新、重连场景。
5. **UI**：v1 以 ViewModel 单测为主（断言 StateFlow 状态机）；Compose 快照测试按需，不强制。
6. **adb 集成测试**（可选）：真起 adb + 模拟器，放 `:integration-test` 源集，CI 可跳过。

## 11. 待后续阶段明确（非 v1）

- Shell 终端页（交互式 `adb shell` 子进程 + ANSI 处理）
- Logcat 实时流（`streamLogcat(): Flow<LogLine>` + 过滤/搜索/缓冲策略）
- 文件 push/pull（拖拽、进度、断点）
- 投屏（scrcpy 集成 vs `adb exec-out screencap` 轮询）
- 调试/系统操作（端口转发、`adb pair`、reboot/recovery/sideload、root/remount、monkey 压测）

## 12. 风险与缓解

| 风险 | 缓解 |
|---|---|
| adb 文本输出格式随版本变化导致 Parser 脆弱 | Parser 单测覆盖多版本 fixture；解析失败保留 adb 原文兜底 |
| `track-devices` 长驻进程在 server 重启/多客户端时行为不稳 | 指数退避自愈 + 降级为定期 `adb devices` 轮询（轮询作为 fallback） |
| 内置 adb 版本与用户系统 adb 冲突 | 可配置优先级：覆盖路径 > 内置 > PATH；设置项清晰可见 source |
| KMP/JVM 打包体积（含 JRE） | jpackage 产出 MSI；便携版用压缩；JRE 体积可接受（非 Chromium 量级） |
