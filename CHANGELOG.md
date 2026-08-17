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
- **多语言 i18n**（`02fa1c7`+`1eeb871`+`b8c5479`）：`ui/i18n/Locale.kt`（enum ZH/EN，可扩展）+ `Strings.kt`（Compose-state-backed，`t(key)` 查表，68 个 key 的 zh/en）；默认中文；Settings 页可切换语言、存进 `Settings.locale`；启动时在 UI 线程（`Main` 里 `runBlocking { settings.load() }` + `Strings.set`）设初始语言（不能在后台 scope 设——会在线程外创建 Compose state，首帧读取抛 "state created after the snapshot was taken"）；`Strings.set` 用 `Snapshot.withMutableSnapshot` 保证跨线程写安全。所有 UI 文案走 `Strings.t(...)`；`:core` 不含用户文案（异常信息保留 adb 原文）。导出报告的段标题随当前语言翻译。
- **测试修复**（`1eeb871`）：i18n 任务跑全量 desktop 测试时暴露 3 个遗留失败——DeviceInfo/AppManager VM 的自动刷新 collector（`init` 里 `selectedSerial.collect`）在测试 scope 上永不完成 → `UncompletedCoroutinesError`（改为 tracked `refreshJob` + `stop()`，测试调 `vm.stop()`）；Screenshot 测试因 banner 剥离修复后要求 PNG 签名而喂了无签名字节 → NPE（测试改喂 `0x89...` 签名前缀）。教训：后续功能改动后**只编译不跑测试**会漏这类回归——以后 UI 改动一并跑 `:desktop:test`。

### 已知技术债（不影响 v1 使用）
- `DeviceHistoryStore` / `SettingsStore` 用 `Dispatchers.IO`，`runTest` 下需轮询规避竞态——正经做法是给 store 注入 dispatcher，留作后续。i18n 任务的测试批量运行是第一个因此实际挂掉的场景。
- `DeviceRepository` 构造里 `runBlocking` 读首帧历史（毫秒级，可接受）。
- `NoDeviceSelectedException` 成了死代码（守卫移到 UI 层）；`DeviceTracker.clock` 参数未用；`DeviceRepository` 有空的 `scope.launch{}`；`JvmAdbProcessRunner.run` 大输出下 readText-before-waitFor 有死锁风险（v1 adb 输出都小，无影响）；`AdbProcessRunner.startStream` 已无调用方（DeviceTracker 改轮询后），保留为接口死代码；`DeviceTracker` FAILED 分支日志写 "backoff" 实为固定 2s 轮询。
- 镜像配置（`settings.gradle.kts` 的 Aliyun + `~/.gradle/init.gradle` + wrapper 的 Tencent）针对中国网络；Aliyun 是公开的、全球可达，非中国网络也能用（只是 Aliyun 优先）。若有非中国贡献者/CI，可条件化或回退到 canonical 源。
- **打包构建需完整 JDK 21**（含 jpackage + jmods）：Android Studio 的 JBR **不含** jpackage/jmods，只能运行/编译；要出 MSI/便携版需另装 Temurin/Zulu 并设 `JAVA_HOME`，MSI 还需 WiX 3.11。

### v1 范围外（后续阶段）
Shell 终端页 / Logcat 实时流 / 文件 push-pull / 投屏（scrcpy）/ 调试与系统操作（端口转发、`adb pair`、reboot/recovery、root/remount、monkey 压测）。见 spec §11。

---

## v2: Logcat 实时流 (branch `feat/logcat`, 2026-08-17)

### 功能
- **实时 logcat 查看器**：长驻 `adb logcat -v threadtime` 子进程，逐行流入 `:core` `LogcatController`（~10000 行环形缓冲，溢出最旧）。
- **过滤**：级别下拉（V/D/I/W/E/F 多选）+ 一个文本输入框（子串匹配整行 = tag+消息+时间戳+pid）。（tag/msg/pid 分字段 + package 过滤留后续——logcat threadtime 无 package 字段，package 过滤需 `pidof` 解析，较复杂，暂不做。）
- **控制**：暂停/恢复（暂停丢新行不积压）、清空、导出为 `logcat_<stamp>.txt`（+ Open/Open-folder 链接）、复制可见行。
- **流式自愈**：子进程死亡/异常 → 指数退避重启（1s→…→30s），3 次失败置 `FAILED` 继续尝试。
- **设备切换自动重起**：`LogcatViewModel` `selectedSerial.collect { controller.start(it) }`。
- **UI**：按级别着色（V/D 灰、I 黑、W 橙、E/F 红）、自动滚底（向上滚后右下角浮动 ↓ 跳回最新）、内联错误。

### 真机测试发现并修复的问题
- **`LogcatController` 最初用 `limitedParallelism(1)` 串行化 deque 变更 → 阻塞 `readline()` 占着单线程，把 `setFilters`/`clear` 饿死**（过滤/清空无反应）。改用 `Mutex`：runLoop 跑在多线程 `scope`（阻塞 readline 只占一个 Default 线程、不持锁），`onLine`/`clear`/`setFilters`/`start`-clear 用 `mutex.withLock` 串行化。教训：单线程 dispatcher confinement 不能给"含阻塞 I/O 的 runLoop"用，会饿死同 dispatcher 的控制调用。
- **过滤输入框绑定 controller 异步 StateFlow → 复选框不能取消、文本框不能打字**：`setFilters` 异步（mutex/serialDispatcher 路由），输入读旧快照。改为屏幕**本地 Compose 状态**驱动输入（同步），`setFilters` 异步只管过滤。
- **`LazyColumn key={it.raw.hashCode()}` → 重复行同 key 运行时崩溃**（`IllegalArgumentException`）→ 改 `itemsIndexed`（位置 key）。
- **自动滚底 `isScrollInProgress` 判定只覆盖"正在拖"**，用户静止向上滚仍被拽到底 → 改 `derivedStateOf` sticky-bottom（按最后可见 item 判定）+ 浮动 ↓ 跳回按钮。
- **导出 `runCatching` 静默吞 IO 错误** → 加内联 `exportError` 红条。
- **`catch(Throwable)` 吞 `CancellationException`** → 重抛（`stop()` 的 cancel 干净退出 runLoop，无瞬态 RECONNECTING）。

### 已知技术债（logcat）
- `stream: AdbStream?` 跨 `stop()`/`runLoop` 未同步 → stop-during-reconnect 边缘下一瞬子进程泄漏（非崩溃）。
- 取消选中（selectedSerial→null）不停 logcat 流（VM `it?.let{}` 跳过 null）→ 旧设备后台流残留（一行修）。
- 暂停测试仅断言 status-toggle（channel 在 pause 前已 emit 完，文档化）。
- `Color.Black`/`Gray` 在暗色主题下 I/V/D 可读性差（v1 可接受）。
- tag/msg/pid 分字段过滤 + package 过滤未做（见上"功能"注）。

---

## v2: Shell 终端页 (branch `feat/shell`, 2026-08-17)

### 功能
- **一键开系统终端**跑 `adb -s <serial> shell`——侧栏 Shell 页一个 "Open Shell" 按钮，选中设备后启用。点击 → 拉起 OS 原生终端（Windows 优先 `wt.exe`，无则 `cmd.exe /K`），直接看到 `MTK9632:/ $` 真提示符。
- OS 终端原生处理 PTY / ANSI / 命令历史 / vim/top/less——app 不做 in-app 终端模拟。
- adb 路径用 `AdbLocator` 解析（override > bundled > PATH），含空格加引号；进程 detached（不 `waitFor`）。

### 实现要点
- `:desktop/platform/ShellLauncher` 接口 + `WindowsShellLauncher`（`buildArgs` 纯函数可测：wt-vs-cmd + 引号；`open` detached 起进程）+ `FakeShellLauncher`（测试捕获）。
- `ShellScreen` 无 VM（纯 UI + 回调）；`onOpenShell` 在 Main 接驳：`runBlocking { root.locator.locate() } → launcher.open`。`ShellScreen` 包 `runCatching` + 内联红条（IOException / AdbNotFoundException 不崩、spec §5 内联错误）。
- `CompositionRoot.locator` 由 `private` 改 `val`（供 Main 调）。
- `:core` **零行改动**——复用 v1 的 `AdbLocator`。

### 已知技术债（shell）
- 错误条硬编码 `Color(0xFFFFCDD2)`（应 `MaterialTheme.colors.error`，暗色主题可读性）。
- SHELL nav 按钮无 `if (vm != null)` 守卫（总是渲染——安全，Main 总接驳）。
- `runBlocking` 在 UI 线程解析 adb 路径（极小读取；可启动时缓存）。
- adb-not-found 点击时内联报错（非 spec §5 "置灰"——需 eager locate 才能预置灰，可接受）。
- 无 VM/Compose 测 ShellScreen（无状态；launcher 由 S1 测；真机冒烟手动）。

