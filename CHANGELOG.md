# Changelog — ADB GUI

记录 v1 的功能、真机测试发现并修复的问题、以及后续增强。便于排查与维护。
设计依据：`docs/superpowers/specs/2026-08-14-adb-gui-design.md`。

## v1 (branch `feat/adb-gui-v1`, 2026-08)

### 已交付功能
- **连接管理核心**：USB + 无线（`adb connect`）连接；多设备并存；设备历史持久化（`%APPDATA%/AdbGui/devices.json`：serial / 别名 / 类型 / 无线 IP:Port / 最后连接时间）；重连历史无线设备一键即连。
- **设备发现**：每 2 秒轮询 `adb devices`（见下方"真机修复"——`track-devices` 不可用）。设备插拔最多 2s 延迟反映到 UI。
- **应用管理**：列三方包（`pm list packages -3`）、安装（`install -r`）、卸载、清数据；失败内联显示 + adb 原文折叠。
- **设备信息**：`getprop` 解析型号 / Android 版本 / SDK / 序列号 / ABI / 分辨率。
- **截屏**：`adb -s <serial> exec-out screencap -p`；剥设备 shell 初始化 banner 后交 Skia 解码显示；Save 对话框默认文件名带时间戳（`screenshot_yyyyMMdd-HHmmss.png`）；Save 后提供 **Open image**（默认看图软件）+ **Open folder**（资源管理器选中该文件）两个可点击链接。
- **设置**：adb 路径覆盖（override > 内置 > PATH；v1 不内置 adb）；日志级别切换（INFO/DEBUG，默认 INFO）；打开日志文件夹；导出日志。
- **日志**：`%APPDATA%/AdbGui/logs/adbgui.log`，滚动 5 文件 × 2MB；记 adb 命令调用、设备轮询、server 生命周期、重连、未捕获异常。
- **打包配置**：jpackage MSI + 便携 AppImage（`desktop/build.gradle.kts` nativeDistributions + `packaging/README.md`）。

### 真机测试发现并修复的问题
- **`adb track-devices` 输出不是纯文本，是 adb 线协议帧**（`0017` = 4 位十六进制长度前缀 + 负载，无分隔符）——`TrackDevicesParser` 把 `001710.0.6.100:5555` 当成 serial，导致所有 `adb -s <serial>` 报 "device not found"。
  - 修复（`133d856`）：`DeviceTracker` 改为每 2s 轮询 `adb devices`（干净文本，`DevicesListParser.parse` 解析），不再维持 track-devices 流。代价：设备变化 ≤2s 延迟（v1 可接受）。**注意：spec §5.3 原写的是 track-devices 流方案，实际已改为轮询——spec 与实现在此处不一致，以本 CHANGELOG + 代码为准。**
- **`adb exec-out screencap -p` 的二进制流被设备 shell 初始化 banner 污染**（VIDAA 安卓电视会在 stdout 前面吐 "Init wrapper sys mutex successful. Pid:NNNN\n"），导致 Skia `Image.makeFromEncoded` 解码失败（"Failed to decode image"）。
  - 修复（`0adb784`）：`CommandRunner.screenshot` 扫描 PNG 签名 `89 50 4E 47 0D 0A 1A 0A`，从签名处截取到末尾，剥掉前置 banner；找不到签名抛 `AdbCommandException`。
- **错误路径三处缺口**（spec §7 要求命令失败内联显示）：(1) `DeviceListViewModel.connect` / `DeviceInfoViewModel.load` / `ScreenshotViewModel.capture` 失败后 `_error` 未清空，重试成功后旧错误残留；(2) `AppManagerViewModel.uninstall`/`clearData` 缺 try/catch，失败抛到 SupervisorJob 被吞、无内联提示；(3) `CommandRunner.screenshot` 对空字节未报错（离线设备 → 空图无提示）。
  - 修复（`e54440d`）：三处 VM 在方法入口清 `_error`；`uninstall`/`clearData` 补 try/catch 镜像 `install()`；`screenshot` 空字节 → 抛 `AdbCommandException`；各加回归测试。

### 后续增强
- **截屏文件名带时间戳 + Open image / Open folder 链接**（`7c6b0e6`）：Save 默认文件名 `screenshot_yyyyMMdd-HHmmss.png`；Save 成功后显示路径并提供两个可点击链接（Windows 上 Open folder 用 `explorer.exe /select,<path>` 选中文件）。
- **自动选中首个在线设备**（`d3df964`+`4743234`）：应用启动/当前选中无效时自动选第一个**在线**设备；选中要求设备在线——设备变 offline 即视为无效，自动清空旧数据（不再残留上一台设备信息）。永不抢走用户已选且仍在线的设备。
- **选中行高亮 + 切换设备自动刷新**（`b27bd59`）：侧栏选中行加浅色主色调背景；DeviceInfo/AppManager 的 VM 加 `selectedSerial.collect { load() }`，切设备即刷新（不用手点）。
- **重连已断开设备 + 右键上下文菜单**（`1fa94fd`）：`DeviceListViewModel.reconnect(ip,port)` 走 `repo.connectWireless`；设备行右键（`PointerButton.Secondary`）或点 ⋮ 弹菜单：Reconnect（仅无线+已断开）/ Rename / Disconnect / Forget。
- **导出设备详细信息 + Forget 确认弹窗**（`4ab6933`+`8d3c21a`）：DeviceInfo 加 Export 按钮，跑 getprop / wm size / wm density / meminfo / cpuinfo / dumpsys battery / df /data 拼成 .txt（每段独立、单段失败不中断），顶部带 Summary 概要段；Save 文件名 `deviceinfo_yyyyMMdd-HHmmss.txt`；存完给 Open/Open folder 链接。Forget 改为确认弹窗防误删。
- **多语言 i18n**（`02fa1c7`+`1eeb871`）：`ui/i18n/Locale.kt`（enum ZH/EN，可扩展）+ `Strings.kt`（Compose-state-backed，`t(key)` 查表，68 个 key 的 zh/en）；默认中文；Settings 页可切换语言、存进 `Settings.locale`；启动时 CompositionRoot 设初始语言。所有 UI 文案走 `Strings.t(...)`；`:core` 不含用户文案（异常信息保留 adb 原文）。导出报告的段标题随当前语言翻译。
- **测试修复**（`1eeb871`）：i18n 任务跑全量 desktop 测试时暴露 3 个遗留失败——DeviceInfo/AppManager VM 的自动刷新 collector（`init` 里 `selectedSerial.collect`）在测试 scope 上永不完成 → `UncompletedCoroutinesError`（改为 tracked `refreshJob` + `stop()`，测试调 `vm.stop()`）；Screenshot 测试因 banner 剥离修复后要求 PNG 签名而喂了无签名字节 → NPE（测试改喂 `0x89...` 签名前缀）。教训：后续功能改动后**只编译不跑测试**会漏这类回归——以后 UI 改动一并跑 `:desktop:test`。

### 已知技术债（不影响 v1 使用）
- `DeviceHistoryStore` / `SettingsStore` 用 `Dispatchers.IO`，`runTest` 下需轮询规避竞态——正经做法是给 store 注入 dispatcher，留作后续。i18n 任务的测试批量运行是第一个因此实际挂掉的场景。
- `DeviceRepository` 构造里 `runBlocking` 读首帧历史（毫秒级，可接受）。
- `NoDeviceSelectedException` 成了死代码（守卫移到 UI 层）；`DeviceTracker.clock` 参数未用；`DeviceRepository` 有空的 `scope.launch{}`；`JvmAdbProcessRunner.run` 大输出下 readText-before-waitFor 有死锁风险（v1 adb 输出都小，无影响）；`AdbProcessRunner.startStream` 已无调用方（DeviceTracker 改轮询后），保留为接口死代码；`DeviceTracker` FAILED 分支日志写 "backoff" 实为固定 2s 轮询。
- 镜像配置（`settings.gradle.kts` 的 Aliyun + `~/.gradle/init.gradle` + wrapper 的 Tencent）针对中国网络；Aliyun 是公开的、全球可达，非中国网络也能用（只是 Aliyun 优先）。若有非中国贡献者/CI，可条件化或回退到 canonical 源。
- **打包构建需完整 JDK 21**（含 jpackage + jmods）：Android Studio 的 JBR **不含** jpackage/jmods，只能运行/编译；要出 MSI/便携版需另装 Temurin/Zulu 并设 `JAVA_HOME`，MSI 还需 WiX 3.11。

### v1 范围外（后续阶段）
Shell 终端页 / Logcat 实时流 / 文件 push-pull / 投屏（scrcpy）/ 调试与系统操作（端口转发、`adb pair`、reboot/recovery、root/remount、monkey 压测）。见 spec §11。
