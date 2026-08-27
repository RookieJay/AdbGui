# UI 易用性打磨 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从用户视角补齐四组高频易用性缺口——对话框键盘提交/聚焦、加载 spinner 统一、破坏性操作二次确认、侧栏空状态引导。

**Architecture:** 全部改动落在 `:desktop` 的 Compose 交互层（`ui/*.kt`）。不新增 `:core` 命令能力、不改 `DeviceRepository`/`CommandRunner`、不碰解析层。因此本计划不触发 `:core` TDD 红线，也不引入新 adb 命令。

**Tech Stack:** Compose Multiplatform（Material 2），Kotlin/JVM 21，Gradle wrapper。

**Spec:** `docs/superpowers/specs/2026-08-14-adb-gui-design.md`（设计 spec，错误处理约定 §"错误本地化，不弹模态打断"与"可操作指引"——本计划新增的二次确认 dialog 针对的是**破坏性数据操作**，不违反"命令失败不弹模态"约定，因为确认发生在执行前、且针对不可逆操作，与现有 Uninstall/Reboot/Forget 确认 dialog 一致）。

## Global Constraints

- **测试策略**：`desktop/build.gradle.kts:14-15` 只引入 `kotlin("test")` + `libs.coroutines.test`，**无 Compose UI 测试框架**。本计划所有改动为纯 Compose 交互（键盘事件、focus、spinner 显示、确认 dialog），按 CLAUDE.md「UI 以 ViewModel 单测为主…Compose 快照测试按需，不强制」，**每个任务以手动运行验证为准，不新增单测**。不新增 `:core` 逻辑，故无 `:core` TDD 适用点。
- **i18n**：所有新增文案必须同时在 `Strings.kt` 的 `zh` map（line 18 起）和 `en` map（line 290 起）各加一条 key，缺一即未完成。复用现有 `ok`/`cancel` 等 key，不重复定义。
- **不越层**：UI 只回调 `DeviceRepository`/现有 VM 方法；本计划不改 VM 签名（Clear data / Clear / push 的 VM 方法已存在）。
- **不引入死代码**：不加"以后可能用"的占位 state 或回调（CLAUDE.md 技术债 §2）。
- **提交**：每个任务一个提交，Conventional Commits，`feat(desktop):` 或 `fix(desktop):`。
- **运行验证**：每个任务结尾运行 `./gradlew :desktop:run`，按"手动验证"清单操作；如旧 MainKt JVM 残留，先 kill 再 run（项目记忆：TaskStop 不杀 app JVM）。

---

## 任务总览

| 组 | 任务 | 文件 | 感知 |
|---|---|---|---|
| A1 | ConnectDialog 键盘提交+聚焦 | ConnectDialog.kt | 高频连接少一次鼠标 |
| A2 | PairDialog 键盘提交+聚焦 | PairDialog.kt | 配对流程少鼠标 |
| A3 | 内联 rename 支持 Esc 取消 | DeviceListPane.kt | 取消重命名顺手 |
| B1 | 侧栏 reconnect 显示 busy spinner | DeviceListPane.kt | 知道"在重连" |
| B2 | scrcpy installing 显示 spinner | DeviceOverviewScreen.kt | 知道"在安装" |
| C1 | Clear data 二次确认 | AppConsoleScreen.kt | 防误清数据 |
| C2 | Logcat Clear 二次确认 | LogcatScreen.kt | 防误清日志 |
| C3 | push 覆盖前确认 | FileExplorerScreen.kt | 防误覆盖设备文件 |
| D1 | 侧栏空设备列表引导卡片 | DeviceListPane.kt | 新用户不盯着空白 |

---

### Task A1: ConnectDialog 自动聚焦首字段 + Enter 提交

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/ConnectDialog.kt:35-91`

**Interfaces:**
- Consumes: `DeviceListViewModel.connect(ip: String, port: Int, onResult)` — 不改签名。
- Produces: 无（纯 UI）。

**改动说明：** 打开连接对话框后自动聚焦 IP 输入框；IP 字段按 Enter/Next 跳到 Port 字段；Port 字段按 Enter（ImeAction.Done）触发 Connect（等价点 Connect 按钮）。空白 IP 的 `127.0.0.1` fallback 行为保持不变（不在本任务范围）。

- [ ] **Step 1: 加 import 与提交动作**

在 `ConnectDialog.kt` 顶部 import 区补：
```kotlin
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
```

在 `ConnectDialog` composable 内、`val busy by ...` 之后，加两个 FocusRequester + 一个 submit lambda：
```kotlin
val ipFocus = remember { FocusRequester() }
val portFocus = remember { FocusRequester() }
val submitConnect = {
    if (!busy) {
        val p = port.toIntOrNull() ?: 5555
        vm.connect(ip.ifBlank { "127.0.0.1" }, p)
    }
}
LaunchedEffect(Unit) { ipFocus.requestFocus() }
```
（`LaunchedEffect` 已 import。）

- [ ] **Step 2: IP 字段接 FocusRequester + ImeAction.Next**

`OutlinedTextField(value = ip, ...)` 加 `modifier = Modifier.fillMaxWidth().focusRequester(ipFocus)`，并把 `keyboardOptions` 改为带 `imeAction = ImeAction.Next`、加 `keyboardActions`：
```kotlin
OutlinedTextField(
    value = ip,
    onValueChange = { ip = it },
    label = { Text(Strings.t("ip_address")) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().focusRequester(ipFocus),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
    keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }),
)
```

- [ ] **Step 3: Port 字段接 FocusRequester + ImeAction.Done → 提交**

Port 的 `OutlinedTextField`：
```kotlin
OutlinedTextField(
    value = port,
    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
    label = { Text(Strings.t("port")) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().focusRequester(portFocus),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { submitConnect() }),
)
```

- [ ] **Step 4: Connect 按钮复用 submitConnect**

把 `Button(onClick = { val p = ...; vm.connect(...) }, enabled = !busy)` 改为：
```kotlin
Button(onClick = submitConnect, enabled = !busy) { Text(Strings.t("connect")) }
```

- [ ] **Step 5: 编译**

Run: `./gradlew :desktop:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 手动验证**

Run: `./gradlew :desktop:run`（先 kill 残留 MainKt JVM）。打开连接对话框：IP 框自动有光标；输完 IP 按 Enter 光标跳到 Port；输完 Port 按 Enter 触发连接（与点 Connect 等价，busy 时按钮置灰且 Enter 不触发）。

- [ ] **Step 7: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/ConnectDialog.kt
git commit -m "feat(desktop): ConnectDialog autofocus IP + Enter-to-submit"
```

---

### Task A2: PairDialog 自动聚焦首字段 + Enter 提交

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/PairDialog.kt:33-149`

**Interfaces:**
- Consumes: `DeviceListViewModel.pair(...)` 与 `vm.connect(...)`，不改签名。
- Produces: 无。

**改动说明：** Phase 1 打开时聚焦 `pairIp`；`pairPort` Enter 跳 `code`；`code` Enter（满 6 位也可）触发 pair（仅当按钮 enabled）。Phase 2 聚焦 `connectIp`；`connectPort` Enter 触发 connect。为可控，仅给首字段加自动聚焦、给最后字段（code、connectPort）加 onDone 触发主操作；中间字段用 ImeAction.Next 链式跳转。

- [ ] **Step 1: 加 import**

```kotlin
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
```

- [ ] **Step 2: 声明 FocusRequester + 提交 lambda**

在 `var connectPort by remember ...` 之后加：
```kotlin
val pairIpFocus = remember { FocusRequester() }
val pairPortFocus = remember { FocusRequester() }
val codeFocus = remember { FocusRequester() }
val connectIpFocus = remember { FocusRequester() }
val connectPortFocus = remember { FocusRequester() }

val submitPair = {
    if (!busy && pairIp.isNotBlank() && pairPort.isNotBlank() && code.isNotBlank()) {
        connectIp = pairIp
        vm.pair(pairIp, pairPort.toIntOrNull() ?: 0, code) { r ->
            if (r.success) { paired = true; vm.clearError() }
        }
    }
}
val submitConnectPhase2 = {
    if (!busy && connectIp.isNotBlank() && connectPort.isNotBlank()) {
        vm.connect(connectIp, connectPort.toIntOrNull() ?: 0) { r -> if (r.success) onDismiss() }
    }
}
// 聚焦首字段：phase 1 → pairIp；切到 phase 2 → connectIp
LaunchedEffect(paired) {
    if (!paired) pairIpFocus.requestFocus() else connectIpFocus.requestFocus()
}
```
（`LaunchedEffect` 已 import。）

- [ ] **Step 3: Phase 1 字段接 focus + imeAction**

`pairIp` 字段 modifier 加 `.focusRequester(pairIpFocus)`，`keyboardOptions` 加 `imeAction = ImeAction.Next`，加 `keyboardActions = KeyboardActions(onNext = { pairPortFocus.requestFocus() })`。

`pairPort` 字段加 `.focusRequester(pairPortFocus)`，`imeAction = ImeAction.Next`，`keyboardActions = KeyboardActions(onNext = { codeFocus.requestFocus() })`。

`code` 字段加 `.focusRequester(codeFocus)`，`imeAction = ImeAction.Done`，`keyboardActions = KeyboardActions(onDone = { submitPair() })`。

- [ ] **Step 4: Phase 2 字段接 focus + imeAction**

`connectIp` 字段加 `.focusRequester(connectIpFocus)`，`imeAction = ImeAction.Next`，`keyboardActions = KeyboardActions(onNext = { connectPortFocus.requestFocus() })`。

`connectPort` 字段加 `.focusRequester(connectPortFocus)`，`imeAction = ImeAction.Done`，`keyboardActions = KeyboardActions(onDone = { submitConnectPhase2() })`。

- [ ] **Step 5: 按钮复用 submit lambda**

Phase 1 pair 按钮 `onClick` 体替换为 `submitPair()`（保留 `enabled = !busy && pairIp.isNotBlank() && pairPort.isNotBlank() && code.isNotBlank()`）。

Phase 2 connect 按钮 `onClick` 体替换为 `submitConnectPhase2()`（保留其 `enabled`）。

- [ ] **Step 6: 编译**

Run: `./gradlew :desktop:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 手动验证**

Run: `./gradlew :desktop:run`。打开配对对话框：phase 1 聚焦 IP；Tab/Enter 依次走 port→code；code 按 Enter 触发配对（输入合法时）。配对成功切到 phase 2，`connectIp` 自动聚焦；connectPort 按 Enter 触发连接。busy 期间所有按钮置灰且 Enter 不触发。

- [ ] **Step 8: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/PairDialog.kt
git commit -m "feat(desktop): PairDialog autofocus + Enter-to-submit across phases"
```

---

### Task A3: 内联 rename 支持 Esc 取消

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListPane.kt:175-181`

**Interfaces:** 无外部接口变化。

**改动说明：** 现有 `onPreviewKeyEvent` 只处理 Enter/NumPadEnter 提交。加 `Key.Escape` → 退出 rename 不提交（与点 Cancel 按钮等价）。

- [ ] **Step 1: 扩 onPreviewKeyEvent**

把 `DeviceListPane.kt:175-181` 的 `onPreviewKeyEvent` lambda 改为：
```kotlin
.onPreviewKeyEvent { e ->
    when (e.key) {
        Key.Enter, Key.NumPadEnter -> {
            onRename(aliasDraft.ifBlank { null })
            renaming = false
            true
        }
        Key.Escape -> {
            renaming = false
            true
        }
        else -> false
    }
}
```
`Key.Escape` 复用已 import 的 `Key`（`DeviceListPane.kt:49`）。

- [ ] **Step 2: 编译 + 手动验证**

Run: `./gradlew :desktop:compileKotlin` 然后 `./gradlew :desktop:run`。对某设备开启 rename，按 Esc → 退出且不保存；按 Enter → 保存并退出。

- [ ] **Step 3: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListPane.kt
git commit -m "feat(desktop): inline rename Esc-to-cancel"
```

---

### Task B1: 侧栏 reconnect 显示 busy spinner

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListPane.kt:69,74-84`

**Interfaces:** 无。`vm.busy` 已是 `StateFlow<Boolean>`（`DeviceListViewModel.kt:22-23`），reconnect 已置 busy（`:48-55`）。

**改动说明：** `busy` 已收集（`:69`）但 UI 未用。在头部 Row（`Pair` 按钮与 `+` 之间，或 `+` 前）显示 18dp `CircularProgressIndicator`，让用户看到"正在重连/连接中"。`disconnect`/`forget` 不置 busy（瞬时操作，价值低、难测，不引入）。

- [ ] **Step 1: 加 import**

`DeviceListPane.kt` import 区加：
```kotlin
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.foundation.layout.height
```

- [ ] **Step 2: 头部 Row 插入 spinner**

把 `DeviceListPane.kt:74-84` 的 header Row 改为（在 `Spacer(Modifier.weight(1f))` 之后、`TextButton(pair)` 之前插入 busy spinner）：
```kotlin
Row(
    modifier = Modifier.fillMaxWidth().padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(Strings.t("devices"), style = MaterialTheme.typography.subtitle1)
    Spacer(Modifier.weight(1f))
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp).padding(end = 8.dp),
            strokeWidth = 2.dp,
        )
    }
    TextButton(onClick = { showPair = true }) { Text(Strings.t("pair")) }
    IconButton(onClick = onOpenConnect) {
        Icon(Icons.Filled.Add, contentDescription = Strings.t("connect"))
    }
}
```
（`Modifier.size` 已 import 于 `:14`。）

- [ ] **Step 3: 编译 + 手动验证**

Run: `./gradlew :desktop:compileKotlin` 然后 `./gradlew :desktop:run`。触发一次 reconnect（右键设备 → 重新连接，输一个不可达地址）：侧栏头部出现 spinner 直到 attempt 完成。

- [ ] **Step 4: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListPane.kt
git commit -m "feat(desktop): show busy spinner in device list header during reconnect"
```

---

### Task B2: scrcpy installing 状态显示 spinner

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt:318`

**Interfaces:** 无。

**改动说明：** `scrcpyStatus == "installing"` 当前只显示一行 `Text`（`:318`），首次安装可能耗时数秒，用户分不清"在装"还是"卡住"。改为 `Row { CircularProgressIndicator(18dp); Text(...) }`。

- [ ] **Step 1: 加 import**

`DeviceOverviewScreen.kt` import 区（当前没有 `CircularProgressIndicator`）加：
```kotlin
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.foundation.layout.size
```

- [ ] **Step 2: 替换 installing 分支**

把 `DeviceOverviewScreen.kt:318`：
```kotlin
"installing" -> Text(Strings.t("scrcpy_status_installing"))
```
改为：
```kotlin
"installing" -> Row(verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(8.dp))
    Text(Strings.t("scrcpy_status_installing"))
}
```
（`Row`/`Spacer`/`width` 已 import。）

- [ ] **Step 3: 编译 + 手动验证**

Run: `./gradlew :desktop:compileKotlin` 然后 `./gradlew :desktop:run`。首次进入 Device Overview（scrcpy 未装时）：scrcpy 区显示 spinner + "正在安装…"，安装完成切到 installed。

- [ ] **Step 4: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt
git commit -m "feat(desktop): scrcpy installing state shows spinner"
```

---

### Task C1: Clear data 二次确认

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt:76,181-184`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`（zh map + en map）

**Interfaces:**
- Consumes: `vm.clearData(pkg)` — 已存在，不改。

**改动说明：** Uninstall 已有确认（`AppConsoleScreen.kt:210-220`），Clear data 同样破坏性却无确认。加一个 `confirmClearData` state + `AlertDialog`，与 Uninstall 确认同模式。

- [ ] **Step 1: 加 i18n key（zh + en 各一条）**

在 `Strings.kt` zh map（line 18 起）合适位置加：
```kotlin
"clear_data_confirm_title" to "清除应用数据？",
"clear_data_confirm_body" to "将清除 \"%s\" 的全部数据（含登录态、缓存）。应用本身不会被卸载。",
```
在 en map（line 290 起）对应位置加：
```kotlin
"clear_data_confirm_title" to "Clear app data?",
"clear_data_confirm_body" to "This wipes all data for \"%s\" (logins, cache). The app itself is not uninstalled.",
```

- [ ] **Step 2: 加 confirm state**

`AppConsoleScreen.kt:76` 的 `var confirmUninstall by remember { mutableStateOf<String?>(null) }` 下一行加：
```kotlin
var confirmClearData by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 3: Clear 按钮改为触发确认**

把 `AppConsoleScreen.kt:181`：
```kotlin
OutlinedButton(enabled = !busy, onClick = { vm.clearData(sel) }) { Text(Strings.t("clear")) }
```
改为：
```kotlin
OutlinedButton(enabled = !busy, onClick = { confirmClearData = sel }) { Text(Strings.t("clear")) }
```

- [ ] **Step 4: 加确认 dialog**

在文件末尾的 Uninstall 确认 `AlertDialog`（`:210-220`）之后、`AppConsoleScreen` 函数闭合 `}` 之前，加：
```kotlin
confirmClearData?.let { pkg ->
    AlertDialog(
        onDismissRequest = { confirmClearData = null },
        title = { Text(Strings.t("clear_data_confirm_title")) },
        text = { Text(Strings.t("clear_data_confirm_body").format(pkg)) },
        confirmButton = {
            TextButton(onClick = { vm.clearData(pkg); confirmClearData = null }) { Text(Strings.t("clear")) }
        },
        dismissButton = {
            TextButton(onClick = { confirmClearData = null }) { Text(Strings.t("cancel")) }
        },
    )
}
```

- [ ] **Step 5: 编译 + 手动验证**

Run: `./gradlew :desktop:compileKotlin` 然后 `./gradlew :desktop:run`。选一个包点 Clear：弹确认；取消 → 不清；确认 → 执行 `clearData`。Uninstall 行为不变。

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt
git commit -m "feat(desktop): confirm before clearing app data"
```

---

### Task C2: Logcat Clear 二次确认

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt:43,81`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`

**Interfaces:**
- Consumes: `vm.clear()` — 已存在，不改。

**改动说明：** `LogcatScreen.kt:81` Clear 直接清空、无确认、无撤销。加确认 dialog。

- [ ] **Step 1: 加 i18n key**

zh map 加：
```kotlin
"clear_logcat_confirm_title" to "清空日志？",
"clear_logcat_confirm_body" to "将清空当前缓冲区的所有日志行，无法撤销。",
```
en map 加：
```kotlin
"clear_logcat_confirm_title" to "Clear logs?",
"clear_logcat_confirm_body" to "This clears all buffered log lines and cannot be undone.",
```

- [ ] **Step 2: 加 confirm state**

`LogcatScreen.kt:43` 的 `var exportError by remember { mutableStateOf<String?>(null) }` 下一行加：
```kotlin
var confirmClear by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Clear 按钮改为触发确认**

把 `LogcatScreen.kt:81`：
```kotlin
OutlinedButton(onClick = { vm.clear() }) { Text(Strings.t("clear")) }
```
改为：
```kotlin
OutlinedButton(onClick = { confirmClear = true }) { Text(Strings.t("clear")) }
```

- [ ] **Step 4: 加确认 dialog**

`LogcatScreen` 顶层 `Surface { Column { ... } }` 之后、函数闭合前加（与 `LogcatScreen` 同级，放在 `Surface` 闭合 `}` 之后）：
```kotlin
if (confirmClear) {
    AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(Strings.t("clear_logcat_confirm_title")) },
        text = { Text(Strings.t("clear_logcat_confirm_body")) },
        confirmButton = {
            TextButton(onClick = { vm.clear(); confirmClear = false }) { Text(Strings.t("clear")) }
        },
        dismissButton = {
            TextButton(onClick = { confirmClear = false }) { Text(Strings.t("cancel")) }
        },
    )
}
```
`AlertDialog` 已通过 `import androidx.compose.material.*`（`LogcatScreen.kt:9`）可用。

- [ ] **Step 5: 编译 + 手动验证**

Run: `./gradlew :desktop:compileKotlin` 然后 `./gradlew :desktop:run`。连设备开 Logcat，有日志后点 Clear：弹确认；确认 → 清空；取消 → 保留。

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt
git commit -m "feat(desktop): confirm before clearing logcat buffer"
```

---

### Task C3: push 覆盖前确认

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerScreen.kt:43,103-108`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`

**Interfaces:**
- Consumes: `vm.push(localPath)` — 已存在，不改。

**改动说明：** `FileExplorerScreen.kt:103-108` 的 Upload 菜单项选完本地文件后直接 push，设备端同名文件被静默覆盖。本任务做**信息性确认**（不预查设备端是否已存在——预查需额外 ls 往返，超范围；信息性确认与"覆盖会被覆盖"提示即可）。确认 dialog 显示目标设备路径，提醒同名覆盖。

- [ ] **Step 1: 加 i18n key**

zh map 加：
```kotlin
"push_confirm_title" to "上传到设备？",
"push_confirm_body" to "将上传到：\n%s\n若同名文件已存在，会被覆盖。",
```
en map 加：
```kotlin
"push_confirm_title" to "Push to device?",
"push_confirm_body" to "Will upload to:\n%s\nIf a file with the same name exists, it will be overwritten.",
```

- [ ] **Step 2: 加 pending push state**

`FileExplorerScreen.kt:43` 的 `val savedFile by ...` 下一行加：
```kotlin
var pendingPush by remember { mutableStateOf<Pair<String, String>?>(null) }
```
（`Pair<localPath, devicePath>`。）

- [ ] **Step 3: Upload 菜单项改为设置 pending**

把 `FileExplorerScreen.kt:103-108`：
```kotlin
DropdownMenuItem(onClick = {
    menuOpen = false
    val dlg = FileDialog(Frame(), "Upload", FileDialog.LOAD)
    dlg.isVisible = true
    if (dlg.file != null) vm.push("${dlg.directory}${dlg.file}")
}) { Text(Strings.t("upload")) }
```
改为：
```kotlin
DropdownMenuItem(onClick = {
    menuOpen = false
    val dlg = FileDialog(Frame(), Strings.t("upload"), FileDialog.LOAD)
    dlg.isVisible = true
    if (dlg.file != null) {
        val localPath = "${dlg.directory}${dlg.file}"
        val target = "${if (currentPath.endsWith("/")) currentPath else "$currentPath/"}${dlg.file}"
        pendingPush = localPath to target
    }
}) { Text(Strings.t("upload")) }
```
（顺手把硬编码 `"Upload"` 换成 `Strings.t("upload")`，与本任务强相关。）

- [ ] **Step 4: 加确认 dialog**

`FileExplorerScreen` 的 `Surface { Column { ... } }` 闭合之后、函数闭合前加：
```kotlin
pendingPush?.let { (localPath, target) ->
    AlertDialog(
        onDismissRequest = { pendingPush = null },
        title = { Text(Strings.t("push_confirm_title")) },
        text = { Text(Strings.t("push_confirm_body").format(target)) },
        confirmButton = {
            TextButton(onClick = { vm.push(localPath); pendingPush = null }) { Text(Strings.t("upload")) }
        },
        dismissButton = {
            TextButton(onClick = { pendingPush = null }) { Text(Strings.t("cancel")) }
        },
    )
}
```
`AlertDialog` 已通过 `import androidx.compose.material.*`（`FileExplorerScreen.kt:10`）可用。

- [ ] **Step 5: 编译 + 手动验证**

Run: `./gradlew :desktop:compileKotlin` 然后 `./gradlew :desktop:run`。连设备开 File Explorer，右键某文件 → Upload，选本地文件：弹确认显示目标设备路径；取消 → 不上传；确认 → 执行 push。

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerScreen.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt
git commit -m "feat(desktop): confirm before push overwrites device file"
```

---

### Task D1: 侧栏空设备列表引导卡片

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListPane.kt:88-107`

**Interfaces:** 无。复用 `EmptyState`（已在 `AppShell.kt:152-158` 使用）与 `onOpenConnect`/`showPair`。

**改动说明：** 当 `devices` 为空时 `LazyColumn` 什么都不渲染，用户盯着空白。在列表区域加一个空状态卡片：图标 + `no_device_hint` 文案 + "连接设备"主按钮（`onOpenConnect`）+ "配对"次按钮（`showPair = true`）。复用 i18n key `no_device_hint`、`connect_first_device`、`pair`，不加新 key。

- [ ] **Step 1: LazyColumn 空状态分支**

把 `DeviceListPane.kt:88-107` 的 `LazyColumn(...) { items(devices, key = { it.serial }) { device -> DeviceRow(...) } }` 改为：
```kotlin
LazyColumn(
    modifier = Modifier.fillMaxWidth().weight(1f),
    contentPadding = PaddingValues(vertical = 4.dp),
) {
    if (devices.isEmpty()) {
        item {
            EmptyState(
                title = Strings.t("no_device_hint"),
                actionLabel = Strings.t("connect_first_device"),
                onAction = onOpenConnect,
                secondaryActionLabel = Strings.t("pair"),
                onSecondaryAction = { showPair = true },
            )
        }
    } else {
        items(devices, key = { it.serial }) { device ->
            DeviceRow(
                device = device,
                selected = selected,
                onRename = { newAlias ->
                    vm.setAlias(device.serial, newAlias)
                },
                onForget = { vm.forget(device.serial) },
                onDisconnect = { vm.disconnect(device.serial) },
                onSelect = { onSelect(device) },
                onReconnect = onReconnect,
            )
        }
    }
}
```

- [ ] **Step 2: 给 EmptyState 加 secondary action 参数**

`EmptyState.kt` 当前只支持单个 `actionLabel`/`onAction`。打开 `desktop/src/main/kotlin/com/adbgui/desktop/ui/EmptyState.kt`，在现有 `actionLabel: String? = null` / `onAction: (() -> Unit)? = null` 旁边新增两个可选参数：
```kotlin
secondaryActionLabel: String? = null,
onSecondaryAction: (() -> Unit)? = null,
```
在主 `Button` 之后加一个次级 `TextButton`（仅当提供时）：
```kotlin
if (secondaryActionLabel != null && onSecondaryAction != null) {
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
}
```
（确认 `TextButton`/`Spacer`/`height` 已 import；若 `height` 缺失则补 `import androidx.compose.foundation.layout.height`。）

- [ ] **Step 3: 编译 + 手动验证**

Run: `./gradlew :desktop:compileKotlin` 然后 `./gradlew :desktop:run`。无设备连接时侧栏列表区显示引导卡片（文案 + "连接设备"主按钮 + "配对"次按钮）；点连接 → 打开 ConnectDialog；点配对 → 打开 PairDialog。有设备时不显示卡片。

- [ ] **Step 4: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListPane.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/EmptyState.kt
git commit -m "feat(desktop): empty device list shows connect/pair guidance card"
```

---

## Self-Review

**1. Spec coverage** — 四组用户痛点（对话框键盘摩擦 / loading 不一致 / 破坏性无确认 / 侧栏空白）均有任务覆盖。已剔除 disconnect/forget 置 busy（瞬时、难测、低价值，避免技术债）。Settings path clear（清单第 10 项）与 blank-ip fallback（第 15 项）不在用户选定的四组内，不在本计划。

**2. Placeholder scan** — 全部步骤含具体代码或确切行号；无 TBD/"add appropriate"。

**3. Type consistency** — `submitConnect`/`submitPair`/`submitConnectPhase2`/`pendingPush`/`confirmClearData`/`confirmClear`/`confirmClear`（Logcat）/`pairIpFocus` 等命名在各自任务内自洽，跨任务无依赖（各任务独立提交）。`EmptyState` 新增参数为可选，不破坏 `AppShell.kt:152-158` 既有调用（未传 secondary 即不渲染，行为不变）。

**风险提示：** A2 的 `LaunchedEffect(paired)` 聚焦切换——若 Compose 在 phase 切换重组时序导致 focus 丢失，回退为 `LaunchedEffect(Unit)` 仅聚焦 phase1 首字段（phase2 手动点）。若 B1 的 `CircularProgressIndicator` 与 `IconButton` 在 280dp 侧栏挤位，缩小 spinner 到 16dp 或移到标题左侧。手动验证阶段确认。
