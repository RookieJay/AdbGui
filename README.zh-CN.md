# ADB GUI

[English](README.md) | **中文**

一个 Windows 优先的 Compose Multiplatform 桌面 GUI，封装 Android Debug Bridge（`adb`），面向想免终端完成常用 adb 流程的开发与测试人员：连接设备、管理应用、查看设备信息、截屏、抓日志、投屏、文件管理、系统信息查询。Kotlin Multiplatform 工程（`:core` 纯 Kotlin 逻辑 + `:desktop` Compose Desktop UI），跑在 JVM 上。

**状态：v2 已交付大量功能（见下），仍在持续迭代。** 导航页：设备概览 / 应用控制台 / 日志 / 系统操作 / 系统信息 / 文件浏览器 + 设置；截屏与投屏为独立窗口。

> 本中文 README 反映 v2 现状。英文版仍为 v1 时期描述，待同步更新。

## 功能

### 连接与设备管理
- **设备连接** —— USB（授权后自动识别）与无线（`adb connect <ip:port>`），多设备侧栏并存；连接历史持久化（`devices.json`），断开的历史无线设备**一键重连**。
- **设备发现** —— 每 ~2s 轮询 `adb devices`（插拔 2s 内反映到 UI）。自动选中首个在线设备让功能页立即可用；从不抢占用户已选的在线设备；所选设备离线时清空陈旧数据。
- **选择 UI** —— 选中设备行高亮；右键设备行打开上下文菜单（重连 / 重命名 / 断开 / 忘记），“忘记”需确认。

### 设备概览页
- **设备信息** —— 型号 / 品牌 / 厂商 / Android 版本 / SDK / 序列号 / ABI / 分辨率（`getprop`），切换设备自动刷新。**导出**完整设备详情报告（getprop + wm size/density + meminfo + cpuinfo + 电池 + 磁盘）为带时间戳的 `.txt`，保存后给“打开 / 打开所在文件夹”链接。
- **截屏** —— 独立 Compose 窗口；点击 → 加载 → 截图完成弹窗；`ContentScale.Inside` 不放大保清晰；**保存**带时间戳 PNG（`screenshot_yyyyMMdd-HHmmss.png`），保存后给打开图片 / 打开文件夹链接；支持复制到剪贴板。
- **虚拟遥控器** —— D-pad（上下左右 + 确认）+ Back / Home / Menu + 可配置自定义按钮（`adb shell input keyevent`，按钮存 `settings.json`）。
- **文本输入** —— 遥控器区下方文本框 + 发送按钮（`adb shell input text`，Enter 即发送，发送后清空）。
- **设备工具** —— Root / Remount / 打开 Shell（G5 把即时工具操作从系统操作页挪到此处；Shell 一键打开 OS 终端跑 `adb -s serial shell`）。

### 应用控制台页
- 列三方包、安装 APK（`install -r`）、卸载、清数据、强制停止、启动、发广播、查 ContentProvider。安装成功显绿块“已安装：<apk 名>”（6s 自动清），失败显红块保留排障；所有错误内联 + adb 原文折叠，无模态。

### 日志页（Logcat）
- 实时流（`logcat -v threadtime`）+ 级别/文本过滤 + 暂停/清空/导出/复制 + 自动滚动到底 + 断流指数退避自愈。

### 系统操作页
- 重启（normal / recovery / bootloader / sideload）+ 确认对话框 + 成功消息。root/remount 在生产构建被拒（`adbd cannot run as root in production builds` 等）时显红（按真值取证路由，非猜）。

### 系统信息页（G2，只读查询）
- 左侧分组命令列表（应用 / 显示 / 系统 / 网络 四组，16 条 `adb shell` 查询），右侧 `SelectableText` 原始输出区（等宽字体）+ 复制 + 导出（`sysinfo_<ts>.txt`）。顶部包名下拉，需选包的命令（`pm path {pkg}` / `dumpsys package {pkg}` / `dumpsys meminfo {pkg}`）在选中包前禁用；`{pkg}` 替换前过正则守卫防注入。命令清单数据驱动，加命令只改数据不改 UI。

### 文件浏览器页
- `ls -la` 解析（支持 ISO `YYYY-MM-DD` 与 `Mon DD` 两种日期格式，兼容 TCL 等非标准 `ls` 布局）、push/pull、四类图标（按 `test -d` 批量判定目录/文件/apk/图片）、盲探 Permission denied 目录、loading、15s 超时、友好大小显示、保存路径。

### 投屏（scrcpy，独立窗口 / 外部窗口）
- 内置 scrcpy-win64 v4.1（自动解压到 `%APPDATA%/AdbGui/scrcpy/`）；`ScrcpyLocator`（override > 内置 > PATH）启动外部 SDL 窗口。启动选项面板（8 项：分辨率限制/保持唤醒/息屏/置顶/全屏/最大帧率/关音频/录制）+ MOD+key 快捷键对话框；选项持久化到 `settings.json`。录制为显式开关 + 选文件夹 + `scrcpy_<ts>.mp4`，**优雅停止**（`WM_CLOSE` 让 scrcpy finalize mp4，按进程 PID 找窗口，不强杀致损）+ 兜底强杀，停止后显示文件路径 + 打开/打开文件夹。按钮状态同步（scrcpy 窗口自行关闭后 Start/Stop 自动归位）。

### 无线配对（adb pair）
- `adb pair <ip:port> <code>`（Android 11+ 无线调试）+ 自动连接 + 状态反馈；配对码不进 DEBUG 日志（脱敏）；错误 i18n。

### 设置页
- adb 路径覆盖（显示“当前使用”解析路径）、日志级别（INFO/DEBUG，默认 INFO）、打开/导出日志、界面语言（中文/English，默认中文）、scrcpy 路径覆盖、**底部显示 adb 客户端版本**（G3，`adb version`）。

### 贯穿性
- **结构化日志** —— `%APPDATA%/AdbGui/logs/adbgui.log`，滚动 5×2MB，每行带 `[MM-dd HH:mm:ss.SSS]` 时间戳；记 adb 命令调用（`-s serial` + 参数 + 退出码 + stdout/stderr 摘要）、设备轮询、server 生命周期、重连、未捕获异常、启动/退出；不记敏感数据。
- **i18n** —— 运行时切换中文/English，设置里切，持久化；所有 UI 字符串走 `Strings.t(...)`。
- **可复制文本** —— 所有状态/错误文本块鼠标可拖选 + Ctrl+C，无右键菜单冲突。
- **打包** —— jpackage MSI + 便携 AppImage（见 `packaging/README.md`）。

## 环境要求

- **JDK 21**（Android Studio 自带 JBR 可运行/构建；打包需完整 JDK——见 [packaging/README.md](packaging/README.md)）。
- **adb** —— 可在 `PATH` 找到，或在设置里配置路径覆盖（解析优先级：override > 内置 > PATH；v1 不内置 adb）。
- 含 Gradle wrapper，无需系统 Gradle。

## 构建与运行

```bash
./gradlew :desktop:run
```

编译 `:core` 与 `:desktop` 并打开 Compose 窗口。未连接设备时侧栏为空，属正常。项目根 `run.bat`（`run.bat clean` 强制重建 core/desktop jar，避免 stale-jar 假象）。

### 镜像配置（国内网络）

工程开箱即为受限国内网络配好：

- `settings.gradle.kts` —— 阿里云镜像（plugin + 依赖解析，先于 mavenCentral/gradlePluginPortal）。
- `gradle/wrapper/gradle-wrapper.properties` —— `distributionUrl` 指向 Gradle 8.11 的腾讯云镜像。
- 用户级 `~/.gradle/init.gradle` 也可能把下载走阿里云/腾讯。

不在受限网络、想用官方源：把上述改回 `gradlePluginPortal()` / `mavenCentral()` / `https://services.gradle.org/distributions/gradle-8.11-bin.zip`。镜像纯为网络便利，代码不依赖。

## 测试

```bash
./gradlew :core:test     # 纯 Kotlin 单测（parser、domain、store）
./gradlew :desktop:test  # ViewModel 状态机测试
```

`:core` 一律 TDD；adb 输出 fixture 真机录制于 `core/src/test/resources/fixtures/`。

## 设置与日志位置

所有运行时状态在 `%APPDATA%/AdbGui/`（即 `C:\Users\<你>\AppData\Roaming\AdbGui\`）：

| 文件 | 用途 |
|---|---|
| `settings.json` | adb 路径覆盖、日志级别、界面语言、scrcpy 路径覆盖与启动选项、自定义遥控按钮等 |
| `devices.json` | 持久化连接历史（一键重连 + 别名） |
| `logs/adbgui.log` | 结构化文件日志（滚动 5×2MB） |

## 打包（MSI / 便携）

见 [packaging/README.md](packaging/README.md)。打包需**完整 JDK 21**（含 `jpackage`/jmods，Android Studio JBR 不含）；MSI 还需 WiX Toolset 3.11 在 `PATH`。便携 AppImage 只需完整 JDK。

## 架构（面向贡献者）

两模块 KMP 布局：

- **`:core`**（纯 Kotlin/JVM，无 UI 依赖）—— 所有 adb 交互（`CommandRunner`、`DeviceTracker` 轮询、`DeviceRepository`）、parser、store（`SettingsStore`、`DeviceHistoryStore`）、日志抽象。完全可单测（不起真 adb——`FakeAdbProcessRunner` 注入录制输出）。
- **`:desktop`**（Compose Multiplatform）—— UI（screens + ViewModels）、平台实现（`JvmAdbProcessRunner`、`WindowsConfigDirProvider`、`FileLogger`）、i18n、composition root。

架构红线（见 `CLAUDE.md`）：`:core` 不依赖 Compose/UI；UI/ViewModel 只读 `DeviceRepository` 的 `StateFlow`、只回调 `DeviceRepository` 方法（永不直接碰 `CommandRunner`/adb）；平台差异藏接口背后；解析与执行分离（adb 文本输出 → 纯函数 `XxxParser.parse`，`CommandRunner` 调它们）。UI 字符串走 `Strings.t(...)`；`:core` 保留 adb 原文不翻译。

## 已知边界与待办

不在当前版本、规划后续：

- 端口转发（`adb forward`）。
- monkey 压测（System Ops 页预留分区，G4）。
- EMBEDDED 模式投屏（scrcpy 嵌入 Compose 画布，JNA `SetParent` 脚手架已留，画布未挂载）。
- `PairResultParser` fixture 真实化（当前测试用字面量，需在 Android 11+ 开启无线调试的设备上录制）。

技术债与各阶段真机修复见 `CHANGELOG.md`。设计 spec：`docs/superpowers/specs/`；实现计划：`docs/superpowers/plans/`。

## 真机冒烟测试

连一台已授权的真实 Android 设备（无线则 `adb connect <ip:port>`，需同网络并开启无线调试）后跑：

1. `./gradlew :desktop:run` —— 窗口打开。
2. **侧栏** —— 设备出现并自动选中、行高亮；右键 → 上下文菜单（重连/重命名/断开/忘记，忘记需确认）。
3. **设备概览** —— 设备信息刷新；截屏独立窗口（保存 PNG + 打开图片/文件夹）；遥控器 D-pad + 自定义按钮；文本输入发送；Root/Remount/打开 Shell。
4. **应用控制台** —— 列三方包；安装 APK 显“已安装”；卸载/清数据；失败内联显红 + adb 原文。
5. **日志** —— 实时流；级别/文本过滤；暂停/清空/导出/复制；自动滚动。
6. **系统操作** —— 重启（各模式，确认对话框）；生产构建 root 被拒显红。
7. **系统信息** —— 选 getprop 看输出；复制/导出；需包命令未选包显红，选包后替换输出；MAC 地址 fallback；失败显红 + adb stderr。
8. **文件浏览器** —— `ls -la`；push/pull；图标分类；Permission denied 盲探。
9. **投屏** —— 启动外部窗口；选项面板；录制 mp4 可在播放器打开；停止后文件路径 + 打开/文件夹；窗口自行关闭后按钮归位。
10. **设置** —— adb 路径覆盖 + “当前使用”解析路径；日志级别；语言切换（即时重渲染，持久化）；底部 adb 版本。

预期：全部通过。
