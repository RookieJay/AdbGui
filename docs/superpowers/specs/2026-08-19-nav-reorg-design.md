# ADB GUI — 导航重组 + App Console + Device Overview 设计文档

- **日期**：2026-08-19
- **状态**：已通过设计评审，待用户审阅
- **分支**：`feat/nav-reorg`（off master `d42e125`）
- **上位 spec**：`docs/superpowers/specs/2026-08-14-adb-gui-design.md` §11

## 1. 背景与目标

v2 功能多了，导航 8 个入口太分散。重组为 5 个更饱满的页面：

1. **Device Overview**：设备信息 + 截图 + 遥控合并（一个"仪表盘"页）
2. **App Console**：原 App Manager（列包+安装卸载）+ 新增启停/重启/广播/Provider 查询
3. Logcat / Shell / System Ops / File Explorer 不变

App Console 是新功能核心：不只是列表+安装卸载，还能精确控制某个应用的启停、发广播、查 ContentProvider。

## 2. 关键决策

| 维度 | 决策 |
|---|---|
| 导航 | 8 → 5 个入口（+Settings） |
| App Console 布局 | 上下分栏：上半包列表，下半操作面板 |
| 启动方式 | monkey（自动找 LAUNCHER）+ am start（精确指定 activity），两者都支持 |
| 广播 extras | 全类型（string/int/bool/long → `--es`/`--ei`/`--ez`/`--el`） |
| Provider 查询 | URI + 可选 where → 原始文本输出（不解析成表格） |
| Device Overview | 合并 DeviceInfo + Screenshot + Remote → 一个屏幕组合 3 个 ViewModel |
| Device Control | 原 Remote 改名，合并进 Overview，不再单独占 nav |
| :core | `CommandRunner` 加 5 方法 + 2 Parser（Broadcast/ContentQuery） |

## 3. 最终导航

```
侧栏:
  Device Overview (信息+截图+遥控)
  App Console      (列包+启停/广播/Provider)
  Logcat
  Shell
  System Ops
  File Explorer
  Settings
```

## 4. 架构与文件

### 4.1 `:core` 新增

```
domain/
├─ Extra.kt                data class Extra(type: ExtraType, key, value)
├─ ExtraType.kt             enum { STRING("--es"), INT("--ei"), BOOL("--ez"), LONG("--el") }
├─ BroadcastResult.kt       data class BroadcastResult(success, message)
adb/
├─ BroadcastResultParser.kt  纯函数 parse(stdout, stderr, exitCode): BroadcastResult
├─ ContentQueryParser.kt     纯函数 parse(stdout): List<Map<String, String>>
├─ CommandRunner.kt          + forceStop / startApp / startAppActivity / sendBroadcast / queryProvider
└─ device/DeviceRepository.kt + 委托 5 方法
```

### 4.2 `:desktop` 重组

```
删除:
  DeviceInfoScreen.kt + DeviceInfoViewModel.kt
  ScreenshotScreen.kt + ScreenshotViewModel.kt
  RemoteScreen.kt + RemoteViewModel.kt（内容保留，合并进 Overview）
  AppManagerScreen.kt + AppManagerViewModel.kt（内容保留，扩展为 Console）

新建:
  DeviceOverviewScreen.kt   组合 3 个 VM（DeviceInfoVm + ScreenshotVm + RemoteVm）
  AppConsoleScreen.kt       包列表 + 操作面板（启停/广播/Provider）
  AppConsoleViewModel.kt   合并原 AppManagerVm + 新操作

改名:
  (原 RemoteViewModel 保留，被 DeviceOverviewScreen 内部使用)

AppShell:
  NavPage 枚举改为 DEVICE_OVERVIEW / APP_CONSOLE / LOGCAT / SHELL / SYSTEM_OPS / FILE_EXPLORER
  删除 DEVICE_INFO / SCREENSHOT / APP_MANAGER / REMOTE
```

### 4.3 CommandRunner 新方法

```kotlin
suspend fun forceStop(serial, pkg: String): String =
    runCmd(serial, listOf("shell", "am", "force-stop", pkg)).stdout

suspend fun startApp(serial, pkg: String): String =
    runCmd(serial, listOf("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")).stdout

suspend fun startAppActivity(serial, pkg: String, activity: String): String =
    runCmd(serial, listOf("shell", "am", "start", "-n", "$pkg/$activity")).stdout

suspend fun sendBroadcast(serial, action: String, uri: String?, extras: List<Extra>): String {
    val args = buildList {
        add("shell"); add("am"); add("broadcast"); add("-a"); add(action)
        if (uri != null) { add("-d"); add(uri) }
        extras.forEach { add(it.type.flag); add(it.key); add(it.value) }
    }
    return runCmd(serial, args).stdout
}

suspend fun queryProvider(serial, uri: String, where: String?): String {
    val args = buildList {
        add("shell"); add("content"); add("query"); add("--uri"); add(uri)
        if (where != null) { add("--where"); add(where) }
    }
    return runCmd(serial, args).stdout
}
```

### 4.4 Parser

**BroadcastResultParser**：`am broadcast` 成功输出 `Broadcasting Intent { act=xxx flg=0x... }`，失败有错误码。`BroadcastResult(success, message)`。

**ContentQueryParser**：`content query` 每行 `Column: value, Column2: value2`。解析成 `List<Map<String, String>>`。

### 4.5 DeviceOverviewScreen

```
┌─────────────────────────────────┐
│ [刷新] [导出报告]                 │
│ 品牌/厂商/型号/Android/SDK/分辨率/ABI│  ← 紧凑属性网格
├─────────────────────────────────┤
│ ─── 屏幕 ───                      │
│ [截图] [保存]                      │
│   设备屏幕预览 (Image)             │  ← 截图预览（未来 scrcpy 替换）
│ 保存后: 路径 + [Open] [Open folder]│
├─────────────────────────────────┤
│ ─── 遥控 ───                      │
│        ↑                           │
│    ←  OK  →                        │  ← D-pad + Back/Home/Menu + 自定义
│        ↓                           │
│ [返回] [主页] [菜单]                │
│ [Vol+] [Vol−] [Mute] [Power] ...  │
└─────────────────────────────────┘
```

**ViewModel 策略**：不合并 VM——`DeviceOverviewScreen` 接收 3 个已有 VM（`DeviceInfoViewModel` + `ScreenshotViewModel` + `RemoteViewModel`），屏幕层组合渲染。各自的单测不变。

### 4.6 AppConsoleScreen

```
┌─────────────────────────────────┐
│ 搜索框 + [安装 APK] + [刷新]       │
├─────────────────────────────────┤
│ 包列表（点击选中）                  │  ← 上半（40% 高度，可滚动）
│ com.foo.bar                        │
│ com.other.app                      │
├─────────────────────────────────┤
│ 选中: com.foo.bar                  │
│ [启动] [停止] [重启] [清数据] [卸载] │
│                                   │  ← 下半（操作面板）
│ ▸ 高级操作（折叠）                  │
│   am start: Activity [____] [启动]  │
│   广播: Action [__] URI [__]       │
│         Extras: [类型▾][key][val]  │
│         [+] [发送]                  │
│   Provider: URI [__] Where [__]    │
│            [查询] 结果文本           │
│ 错误/成功 (SelectableText)         │
└─────────────────────────────────┘
```

**AppConsoleViewModel**：合并原 `AppManagerViewModel`（load/install/uninstall/clearData/listPackages + auto-refresh + stop）+ 新增：
- `forceStop(pkg)` / `startApp(pkg)`（monkey）/ `startAppActivity(pkg, activity)`
- `sendBroadcast(action, uri, extras)` → `broadcastResult` StateFlow
- `queryProvider(uri, where)` → `providerResult` StateFlow
- `restart(pkg)` = forceStop + startApp（两步）

### 4.7 AppShell + Main 变化

- `NavPage`：`DEVICE_OVERVIEW` / `APP_CONSOLE` / `LOGCAT` / `SHELL` / `SYSTEM_OPS` / `FILE_EXPLORER`
- `DeviceOverviewScreen(deviceInfoVm, screenshotVm, remoteVm, selectedSerial)`
- `AppConsoleScreen(appConsoleVm, selectedSerial)`
- Main.kt 构造 AppConsoleViewModel（替代 AppManagerViewModel）

## 5. 错误处理

- 所有命令失败 → `AdbCommandException` → VM catch → `error` → 内联 `SelectableText`
- 广播成功 → `broadcastResult` 显示 adb 输出；失败 → `error`
- Provider 查询 → 原始输出 `providerResult`；adb 错误 → `error`
- 无设备 → 空状态引导

## 6. 测试策略

1. `BroadcastResultParserTest` — 真实 fixture（成功/失败）
2. `ContentQueryParserTest` — 多行 `Column: value` fixture
3. `CommandRunnerTest` — 5 新方法 args 断言 + 失败抛异常
4. `AppConsoleViewModelTest` — 合并原 AppManagerVmTest + 新操作转发
5. UI 手动冒烟

## 7. 范围边界（非本期不做）

- `ps`/`dumpsys` running 状态标记
- 广播 extras 稀有类型（`--esn`/`--efa`）
- Provider 输出解析成表格
- Provider 写入（insert/update/delete）
- `am start` 的 `-d`/`-t`/extras
- scrcpy 实时投屏
