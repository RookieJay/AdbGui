# ADB GUI — Shell 终端页 设计文档

- **日期**：2026-08-17
- **状态**：已通过设计评审，待用户审阅
- **分支**：`feat/shell`（off master `0152da1`，含 v1 + logcat）
- **上位 spec**：`docs/superpowers/specs/2026-08-14-adb-gui-design.md` §11（Shell 终端页）

## 1. 背景与目标

v2 第二个功能：给选定设备**一键打开系统终端**，直接进入 `adb -s <serial> shell`（看到 `MTK9632:/ $` 之类的真 shell 提示符）。

参考其它 adb 客户端的做法：不在 app 内做终端模拟，而是**一个按钮拉起 OS 原生终端窗口**跑 `adb shell`。OS 终端原生处理 PTY / ANSI / 命令历史 / vim/top/less——app 只负责"解析 adb + 选定设备 + 拉起窗口"。

## 2. 关键决策

| 维度 | 决策 |
|---|---|
| 形态 | **一键开外部系统终端**（不 in-app 模拟）。一个 "Open Shell" 按钮。 |
| 终端 | Windows 优先 `wt.exe`（Windows Terminal），无则 `cmd.exe /K`。Mac/Linux 后续加（`Terminal`/`xterm`）。 |
| adb 路径 | 用 `AdbLocator` 解析的 adb 路径（override > bundled > PATH），路径含空格加引号。 |
| 进程 | detached（`ProcessBuilder.start()` 不 `waitFor`），app 不阻塞、不等终端退出。 |
| **不做** | ❌ :core ShellController、❌ `AdbStream.write`、❌ AnsiStripper、❌ in-app 终端组件、❌ 命令历史/scrollback。全部由 OS 终端承担。 |
| :core 改动 | **零行**——`AdbLocator` 已有（v1）；只是 `:desktop` 调它。 |

## 3. 架构与文件

```
:desktop
├─ platform/ShellLauncher.kt        [新] interface ShellLauncher { fun open(adbPath, serial) }
│                                     + WindowsShellLauncher（ProcessBuilder wt/cmd）
│                                     + FakeShellLauncher（测试：捕获命令拼装）
├─ platform/PlatformProviders.kt    或在现有平台文件加 WindowsShellLauncher（沿用 v1 平台组织）
├─ ui/ShellScreen.kt                [新] 一个页面：选中设备 + "Open Shell" 按钮
├─ ui/AppShell.kt                   [改] + NavPage.SHELL + shellLauncher/openShell 回调 + 渲染分支
├─ main/CompositionRoot.kt          [改] 暴露 adbLocator（或解析 adbPath 的方法）+ 构造 ShellLauncher
└─ ui/i18n/Strings.kt               [改] + shell keys（zh/en）
```

`:core` 不动。

## 4. 组件职责

### 4.1 `ShellLauncher`（`platform/ShellLauncher.kt`）
```kotlin
interface ShellLauncher {
    /** Launch an OS terminal running `<adbPath> -s <serial> shell`, detached. */
    fun open(adbPath: String, serial: String)
}
```
测试用 `FakeShellLauncher`（记录最后一次 `open(adbPath, serial)`，不真起进程）——单测命令拼装。

### 4.2 `WindowsShellLauncher`
```kotlin
class WindowsShellLauncher : ShellLauncher {
    override fun open(adbPath: String, serial: String) {
        val quoted = if (adbPath.contains(' ')) "\"$adbPath\"" else adbPath
        val cmd = "$quoted -s $serial shell"
        // Prefer Windows Terminal if present; fall back to cmd.
        val pb = if (wtAvailable()) ProcessBuilder("wt.exe", "cmd", "/K", cmd)
                 else ProcessBuilder("cmd.exe", "/K", cmd)
        pb.redirectErrorStream(true).start()   // detached — don't waitFor
    }
    private fun wtAvailable(): Boolean =
        runCatching { ProcessBuilder("where", "wt.exe").start().waitFor() == 0 }.getOrDefault(false)
}
```
（`cmd /K` 跑完命令不关窗；用户 close 终端。`wt.exe` 优先更现代，缺则 `cmd`。）

### 4.3 `ShellScreen`（`ui/ShellScreen.kt`）
- 简单页面：显示当前选中设备（serial）+ 一个大 "Open Shell" `Button`（无设备选中时禁用 + 空状态引导）。
- 点击 → `onOpenShell(serial)` 回调（AppShell/Main 注入，调 `launcher.open(adbPath, serial)`）。
- 无 ViewModel（无状态可订阅——纯动作）。

### 4.4 接驳
- `AppShell`：加 `NavPage.SHELL` + 参数 `onOpenShell: (String) -> Unit = {}`（回调，AppShell 不感知 `ShellLauncher`）+ nav 按钮 + 渲染分支（选中设备时显示 `ShellScreen`）。
- `Main.kt` / `CompositionRoot`：构造 `WindowsShellLauncher()`；AppShell 的 `onOpenShell = { serial -> runBlocking { val adb = root.locator.locate(); launcher.open(adb.path, serial) } }`（`AdbLocator.locate()` 是 suspend；`runBlocking` 在按钮回调里解析 adb 路径——一次极小读取，可接受。或启动时预解析 adbPath 缓存，避免每次点击 runBlocking）。`root.locator` 需 public（v1 已是 public）。
- i18n：`shell` / `nav_shell` / `open_shell` / `no_device_selected_shell`（zh+en）。

## 5. 错误处理

| 故障 | 处理 | UI |
|---|---|---|
| adb 找不到 | `AdbNotFoundException` | 引导去设置指向 adb（按钮置灰 + 提示） |
| 终端启动失败（wt/cmd 都没） | `ProcessBuilder.start()` 抛 `IOException` | 内联错误提示 |
| 无设备选中 | 按钮禁用 + 空状态 | "未选择设备" |

延续 v1：错误本地化、不弹模态。

## 6. 测试策略

- `WindowsShellLauncher` / `FakeShellLauncher`：`FakeShellLauncher` 捕获 `open(adbPath, serial)`；单测断言 AppShell 的 `onOpenShell` 回调用了解析的 adbPath + serial。`WindowsShellLauncher` 的命令拼装（`wt` vs `cmd`、引号）用 `FakeShellLauncher` 验证 args（真起 `wt`/`cmd` 放手动冒烟）。
- `:desktop` 测试：AppShell 路由（选中设备 → ShellScreen；点按钮 → 回调）；i18n key 存在。
- 真终端启动 = 手动冒烟（选中 VIDAA TV → Open Shell → 看到 `MTK9632:/ $`）。

## 7. 范围边界（非本期不做）

- **Mac/Linux 终端**（`Terminal.app` / `xterm` / `gnome-terminal`）——v1 只 Windows；架构上 `ShellLauncher` 接口已预留，加平台 actual 即可。
- **In-app 终端组件**（AdbStream.write / ANSI / scrollback / 命令历史）——明确不做（OS 终端原生更优）。
- **多 tab / 会话持久化**——一个按钮一个窗口，够用。
- **Windows Terminal 配置**（profile/字体）——用默认。
