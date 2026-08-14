# CLAUDE.md — ADB GUI

本文件是给 Claude Code（及任何 AI 助手/新成员）的项目维护约束。每条都是为"长期好维护"服务的，改动代码前先读。

## 项目简介

Windows 优先的 adb GUI 桌面工具，服务开发/测试人员：快速连接新设备与历史设备、常用命令 GUI 化。Compose Multiplatform (Kotlin/KMP) 实现。

- 设计 spec：`docs/superpowers/specs/2026-08-14-adb-gui-design.md`
- 实现计划：`docs/superpowers/plans/2026-08-14-adb-gui-v1.md`
- 改动前先读 spec 对应章节；计划里的任务结构是 v1 的事实来源。

## 环境与命令

- JDK 21（已装：`21.0.10`）。用 Gradle wrapper（`./gradlew`），不要假设系统有 `gradle` CLI。
- 运行：`./gradlew :desktop:run`
- 测试：`./gradlew :core:test`（纯 Kotlin，快）、`./gradlew :desktop:test`
- 单测：`./gradlew :core:test --tests "*.包名.类名"`
- 打包：见 `packaging/README.md`（MSI 用 jpackage，需 WiX）。

## 架构红线（不可破）

1. **`:core` 不依赖任何 UI。** 不许 import Compose / `java.awt` / `javax.swing`。所有 adb 交互、设备状态、持久化、解析、日志抽象都在 `:core`，纯 Kotlin 可单测。违反此条 = 重构倒退。
2. **UI 不许直接碰 adb / CommandRunner。** `:desktop` 的 UI 永远只读 `DeviceRepository` 的 `StateFlow`、只回调 `DeviceRepository` 的方法。新增命令能力时扩 `DeviceRepository`/`CommandRunner`，不要让 ViewModel 越层。
3. **`:core` 不起真 adb。** 所有进程交互走 `AdbProcessRunner` 接口（`run`/`runBinary`/`startStream`）。测试用 `FakeAdbProcessRunner` 注入录制好的 adb 输出。需要新命令时，先在 `FakeAdbProcessRunner` 能模拟的接口上落地。
4. **平台差异藏接口背后。** 配置目录、内置 adb 路径、文件对话框这类 OS 相关逻辑放在 `:desktop/platform`，实现 `:core` 里的接口（`BundledAdbProvider`/`PathProbe`/`ConfigDir`）。加新平台 = 新增 actual 实现，`:core` 一行不改。
5. **解析与执行分离。** adb 文本输出 → 纯函数 `XxxParser.parse(...)`；`CommandRunner` 调它们。新 adb 命令 = 加一个 Parser + 一个 CommandRunner 方法，不要把解析塞进 CommandRunner。

## 开发流程

- **`:core` 一律 TDD**：先写失败测试（含 fixture）→ 验证失败 → 最小实现 → 验证通过 → 提交。不要事后补测试。
- **adb 输出 fixture 放 `core/src/test/resources/fixtures/`**，用真实录制的输出，覆盖多版本与成败各码。Parser 脆弱是头号风险，fixture 越全越稳。
- **UI 以 ViewModel 单测为主**：断言 `StateFlow` 状态机；Compose 快照测试按需，不强制。
- **每个任务一个提交**（或任务内逻辑步骤一个提交）。Conventional Commits：`feat(scope):` / `fix(scope):` / `docs:` / `chore:` / `refactor:`。
- 不在默认分支上直接提交大改动前先开分支（项目目前是单分支，按需分支化）。

## 错误处理约定

- **错误本地化，不弹模态打断**。命令失败用 `AdbCommandException(command, exitCode, stderr)`，UI 内联展示 + adb 原文折叠展开。
- **可恢复的自动恢复**。`track-devices` 断流走指数退避（1s→…→30s 封顶）自愈，3 次失败降级为 `adb devices` 轮询；仅短暂置灰提示"正在重连"，不打断用户。
- **找不到 adb 给可操作指引**，不只是报错：`AdbNotFoundException` 引导去设置指向 adb 路径。
- 解析失败保留 adb 原文兜底，不要静默吞。

## 日志约定

- `:core` 依赖 `Logger` 接口，不依赖具体实现。测试用 `InMemoryLogger`/`NoopLogger`。
- 默认 INFO 级；用户排障时在设置里切 DEBUG。
- 记：adb 命令调用（`-s serial` + 参数 + 退出码 + stdout/stderr 摘要）、track-devices 事件、server 生命周期、重连、未捕获异常、启动/退出。
- 不记敏感数据；apk 内容/路径不落 DEBUG 以外，DEBUG 级只记路径不含二进制内容。

## 命名与结构

- 包根：`com.adbgui.core.*`（`:core`）、`com.adbgui.desktop.*`（`:desktop`）。
- 文件按职责拆小：一个 Parser 一个文件（纯函数 `object`）；一个组件一个文件。文件超过 ~300 行就该问"是不是在做两件事"。
- 不可变数据模型放 `domain/`，`data class`，无逻辑。
- 协程 scope：`CommandRunner`/`DeviceRepository` 用注入的 scope，命令可取消；`DeviceTracker` 用长生命周期 scope，不随功能页销毁。
- 持久化文件写用户配置目录（Windows: `%APPDATA%/AdbGui/`）：`settings.json`、`devices.json`、`logs/`。原子写：写 `.tmp` 再 `ATOMIC_MOVE` 重命名。

## v1 范围边界（非 v1 不做）

v1 = 连接管理核心 + 应用管理 + 设备信息 + 截图 + 日志。以下**不在 v1**，新增需先更新 spec 再开任务，不要顺手扩：

- Shell 终端页 / Logcat 实时流 / 文件 push-pull / 投屏（scrcpy）/ 调试与系统操作（端口转发、`adb pair`、reboot/recovery、root/remount、monkey 压测）

## 改动时的检查清单

新增一个 adb 命令时，确认：
1. 有 `XxxParser` + fixture 单测。
2. `CommandRunner` 新方法调 Parser，失败抛 `AdbCommandException`。
3. `DeviceRepository` 暴露该方法（UI 不越层）。
4. 相关 ViewModel 有状态机单测。
5. 日志在 DEBUG 级记录命令与结果摘要。

新增一个 UI 页时，确认：
1. 数据只从 `DeviceRepository` 来，回调只回 `DeviceRepository`。
2. 错误内联，无模态。
3. 无设备选中时按钮禁用 + 空状态引导。
