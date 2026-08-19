# ADB GUI — scrcpy 投屏 设计文档

- **日期**：2026-08-19
- **状态**：已通过设计评审，待用户审阅
- **分支**：`feat/scrcpy`（off master `5609af2`）

## 1. 背景与目标

在 Device Overview 页面内嵌投屏区块。scrcpy v4.1 内置到应用资源，首次运行自动提取。用户可选"独立窗口"或"内嵌窗口"模式。

## 2. 关键决策

| 维度 | 决策 |
|---|---|
| scrcpy 内置 | `scrcpy-win64-v4.1.zip` 放 `desktop/src/main/resources/scrcpy/（11MB） |
| 提取 | 首次运行时从 resources 读 zip → 解压到 `%APPDATA%/AdbGui/scrcpy/` |
| 投屏模式 | 独立窗口（EXTERNAL，默认）+ 内嵌窗口（EMBEDDED，JNA SetParent reparent 到 AWT Canvas → Compose SwingComponent 桥接） |
| 下载源 | GitHub 直连（实测 1.18 MB/s 可用）；设置可配置 URL；所有代理不可达 |
| 投屏位置 | Device Overview 页面底部（Remote 下方），不新增 nav 入口 |
| 平台 | Windows 优先（JNA reparent 是 Windows API；Mac/Linux 后续 |

## 3. 架构与文件

```
:core
├─ domain/ScrcpyOptions.kt     data class ScrcpyOptions(maxSize, stayAwake, turnScreenOff, recordPath?)
├─ domain/ScrcpyMode.kt         enum {EMBEDDED, EXTERNAL}
└─ settings/Settings.kt         + scrcpyPathOverride? + scrcpyDownloadUrl? + scrcpyMode

:desktop
├─ platform/ScrcpyLocator.kt   interface + impl (override > 内置 > PATH)
├─ platform/ScrcpyInstaller.kt   ensureInstalled(): 从 resources 提取 zip → 解压 → 返回路径
├─ platform/ScrcpyLauncher.kt   interface + WindowsScrcpyLauncher (EXTERNAL detached + EMBEDDED SetParent)
└─ ui/DeviceOverviewScreen.kt   + 投屏区块（状态 + 按钮 + EMBEDDED Canvas 区）
```

## 4. 组件

### 4.1 ScrcpyLocator
```kotlin
interface ScrcpyLocator {
    fun locate(): String?  // 返回 scrcpy.exe 路径或 null
}
```
解析顺序：`Settings.scrcpyPathOverride` → `%APPDATA%/AdbGui/scrcpy/scrcpy.exe` → `PATH` 上的 `scrcpy`
找不到 → UI 显示"正在安装..."。

### 4.2 ScrcpyInstaller
```kotlin
class ScrcpyInstaller(private val configDir: Path) {
    fun ensureInstalled(): String  // 返回 scrcpy.exe 路径
    fun isInstalled(): Boolean
    fun install(): String  // 从 resources 读 zip → 解压
}
```
首次运行：`ensureInstalled()` → 检测 `%APPDATA%/AdbGui/scrcpy/scrcpy.exe` 不存在 → 从 JAR resources 读 `scrcpy-win64-v4.1.zip` → 解压 → 返回路径。

### 4.3 ScrcpyLauncher
```kotlin
interface ScrcpyLauncher {
    fun open(scrcpyPath: String, serial: String, options: ScrcpyOptions, mode: ScrcpyMode)
    fun isRunning(): Boolean
    fun stop()
}

class WindowsScrcpyLauncher : ScrcpyLauncher {
    // EXTERNAL: ProcessBuilder(args).start()（detached）
    // EMBEDDED: 启动 scrcpy → 找 SDL 窗口 → SetParent 到 AWT Canvas → SwingComponent 桥接到 Compose
}
```

### 4.4 Settings 新增
```kotlin
val scrcpyPathOverride: String? = null
val scrcpyDownloadUrl: String? = null  // null = GitHub releases
val scrcpyMode: String = "EXTERNAL"  // EMBEDDED / EXTERNAL
```

### 4.5 Device Overview 投屏区块
```
┌─ 投屏 ──────────────────────┐
│ ✅ scrcpy v4.1 已安装        │
│ 模式: (●)独立 (○)内嵌      │
│ [开始投屏] [停止]            │
│ (内嵌时 Canvas 区域)          │
└───────────────────────────────┘
```

### 4.6 CompositionRoot + Main
- `scrcpyInstaller = ScrcpyInstaller(configDir)`
- `scrcpyLauncher = WindowsScrcpyLauncher()`
- `scrcpyLocator = ScrcpyLocator(settings, configDir)`
- 传给 `DeviceOverviewScreen`

## 5. 错误处理

| 故障 | 处理 | UI |
|---|---|---|
| scrcpy 未安装 | 首次自动提取 → 成功后启用投屏 | "正在安装..." → "已安装" |
| 提取失败 | IOException → _error | "❌ 安装失败" + [重试] + [手动指定路径] |
| 投屏启动失败 | IOException → SelectableText | 错误内联 |
| scrcpy 崩溃 | 检测 isAlive=false → _error | "投屏已断开" + [重新投屏] |
| 无设备 | 按钮禁用 + 空状态 | "未选择设备" |

## 6. 测试

1. `ScrcpyOptions` data class（trivial）
2. `ScrcpyLocator` 接口 + fake — 检测优先级（override > 内置 > PATH）
3. `WindowsScrcpyLauncher` — args 构建（EXTERNAL + EMBEDDED）
4. UI 手动冒烟（开始/停止投屏 + 内嵌/独立切换）

## 7. 范围边界

- scrcpy 在线版本更新（URL 可配置但实际更新逻辑后续）
- scrcpy 参数高级定制（CLI flags 编辑器、多设备同时投屏）
- 录制管理（录制列表、转码、清理）
- Mac/Linux scrcpy 内置
