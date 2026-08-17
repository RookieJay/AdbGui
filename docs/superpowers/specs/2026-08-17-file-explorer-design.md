# ADB GUI — File Explorer 设计文档

- **日期**：2026-08-17
- **状态**：已通过设计评审，待用户审阅
- **分支**：`feat/file-explorer`（off master `146f79e`）
- **上位 spec**：`docs/superpowers/specs/2026-08-14-adb-gui-design.md` §11（文件 push-pull）

## 1. 背景与目标

v2 第五个功能：**设备文件浏览器**（仿 Android Studio Device File Explorer）。用户可浏览设备目录树、在目录间导航、右键操作（上传 push / 保存 pull / 刷新 / 复制路径）。不做删除/新建/重命名。

## 2. 关键决策

| 维度 | 决策 |
|---|---|
| 架构 | **方案 C**：VM 持导航状态 + CommandRunner 单发命令（ls/push/pull），无 controller |
| 起始路径 | `/`（非 root 设备部分子目录会报权限错误，内联显示不崩） |
| 列表命令 | `adb shell ls -la <path>` |
| 导航 | 双击文件夹进入；面包屑 + 返回按钮 |
| 右键菜单 | 上传(push) / 保存(pull) / 刷新 / 复制路径 |
| 不做 | 删除、新建、重命名、文件预览、多选/批量、进度条、路径书签 |
| :core | `LsParser` 纯函数 + `CommandRunner.ls/push/pull` + Repository 委托 |

## 3. 架构与文件

```
:core
├─ domain/FileEntry.kt          data class FileEntry
├─ adb/LsParser.kt              纯函数 parse(stdout): List<FileEntry>
├─ adb/CommandRunner.kt        + ls(serial, path): String / push / pull
└─ device/DeviceRepository.kt  + ls/push/pull 委托

:desktop
├─ ui/FileExplorerViewModel.kt  currentPath/entries/error/busy + navigate/back/refresh/push/pull/copyPath
├─ ui/FileExplorerScreen.kt     面包屑 + LazyColumn + 右键菜单
├─ ui/i18n/Strings.kt           + file_explorer keys
├─ ui/AppShell.kt              + NavPage.FILE_EXPLORER
└─ main/Main.kt                 构造 FileExplorerViewModel
```

## 4. 组件职责

### 4.1 `FileEntry`（`domain/FileEntry.kt`）
```kotlin
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val date: String,
    val permissions: String,
    val raw: String,
)
```

### 4.2 `LsParser`（`adb/LsParser.kt`）
纯函数 `object`，`fun parse(stdout: String): List<FileEntry>`。解析 `ls -la` 输出：
```
drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos
-rw-rw---- 1 root root  123 2020-01-01 12:00 test.txt
```
正则拆 `permissions / links / owner / group / size / date / time / name`。首字符 `d` → isDirectory。跳过 `total` 行 + `.`/`..`。名称含空格的保留完整。`suspend fun` 不需要——纯函数。

### 4.3 `CommandRunner` 加 3 方法
```kotlin
suspend fun ls(serial: String, path: String): String =
    runCmd(serial, listOf("shell", "ls", "-la", path)).stdout

suspend fun push(serial: String, localPath: String, devicePath: String) {
    runCmd(serial, listOf("push", localPath, devicePath))
}

suspend fun pull(serial: String, devicePath: String, localPath: String) {
    runCmd(serial, listOf("pull", devicePath, localPath))
}
```
（`runCmd` 失败抛 `AdbCommandException`；`ls` 返回 stdout 供 `LsParser.parse`。`push`/`pull` 无返回值——成功即完成，失败抛异常。）

### 4.4 `DeviceRepository` 委托
```kotlin
suspend fun ls(serial: String, path: String): String = commands.ls(serial, path)
suspend fun push(serial: String, localPath: String, devicePath: String) = commands.push(serial, localPath, devicePath)
suspend fun pull(serial: String, devicePath: String, localPath: String) = commands.pull(serial, devicePath, localPath)
```

### 4.5 `FileExplorerViewModel`（`ui/FileExplorerViewModel.kt`）
构造 `(repo, selectedSerial: StateFlow<String?>, scope)`。

状态：`currentPath: StateFlow<String>`（初始 `/`）、`entries: StateFlow<List<FileEntry>>`、`error`、`busy`。

方法：
- `navigate(path)`：push `currentPath` 到 `backStack` → `repo.ls(serial, path)` → `LsParser.parse` → `entries` + `currentPath`；失败 → `error`。
- `back()`：pop `backStack` → navigate（不再 push）。
- `refresh()`：重新 ls `currentPath`。
- `push(localPath)`：`val devicePath = "$currentPath/${File(localPath).name}"` → `repo.push` → refresh。
- `pull(devicePath, localSavePath)`：`repo.pull`。
- `selectedSerial.collect { it?.let { navigate("/") } }` + `stop()`。

`backStack`：`ArrayDeque<String>`（内存）。

### 4.6 `FileExplorerScreen`（`ui/FileExplorerScreen.kt`）
- 面包屑：`currentPath` + `←` 返回（backStack 非空时启用）+ 刷新。
- `LazyColumn`（`itemsIndexed`）：目录排前；每行 `📁/📄` + 名称 + 大小 + 日期。双击目录 → `vm.navigate`。
- 右键菜单（`PointerButton.Secondary`）：上传（FileDialog 打开 → `vm.push`）/ 保存（仅文件，FileDialog SAVE → `vm.pull`）/ 刷新 / 复制路径（剪贴板）。
- 无设备 → 空状态；权限错误 → 内联 adb 原文。

### 4.7 接驳
`AppShell` + `NavPage.FILE_EXPLORER` + `fileExplorerVm`。`Main.kt` 构造 VM。i18n 加 `file_explorer`/`nav_file_explorer`/`upload`/`save`/`copy_path`/`refresh`（复用已有 `refresh`）等键。

## 5. 数据流

```
用户双击文件夹 / 右键刷新
  └─ FileExplorerViewModel.navigate(path) / refresh()
      └─ DeviceRepository.ls(serial, path)
          └─ CommandRunner.ls → adb -s serial shell ls -la path → stdout
              └─ LsParser.parse(stdout) → List<FileEntry> → entries StateFlow → UI
右键上传 → FileDialog 选本地文件 → vm.push(localPath)
  → repo.push(serial, local, "$currentPath/$filename") → adb -s serial push → refresh
右键保存 → FileDialog SAVE → vm.pull(devicePath, localPath)
  → repo.pull(serial, device, local) → adb -s serial pull
设备切换 → selectedSerial.collect → navigate("/")
```

## 6. 错误处理

| 故障 | 处理 | UI |
|---|---|---|
| `ls` 权限不足 | `AdbCommandException` | 内联 adb 原文 + 面包屑保持（可返回） |
| `push`/`pull` 失败 | `AdbCommandException` | 内联错误 |
| 无设备选中 | 空状态 | "未选择设备" |
| 切设备 | `navigate("/")` 重新列表 | 旧路径作废 |

## 7. 测试策略

1. `LsParserTest`——真实 `ls -la` fixture（目录+文件+`total`+`.`/`..`+空格名）。断言字段 + 跳过。
2. `CommandRunnerTest`——`ls`/`push`/`pull` args + 失败抛异常。
3. `FileExplorerViewModelTest`——navigate/back/refresh 状态机。
4. UI 手动冒烟（双击导航 + 右键上传/保存 + 复制路径）。

## 8. 范围边界（非本期不做）

删除 / 新建 / 重命名 / 文件预览 / 多选批量 / push/pull 进度条 / 路径书签 / 递归大小。
