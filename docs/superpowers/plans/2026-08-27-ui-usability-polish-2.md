# UI 易用性打磨 第二波 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans to implement task-by-task. Checkbox (`- [ ]`) tracking.

**Goal:** 补齐四组剩余易用性缺口——属性/包名复制、功能页空状态连接按钮、侧栏导航键盘可达、硬编码字符图标化。

**Architecture:** 全部落在 `:desktop` Compose 交互层，不碰 `:core`。复用项目既有的 `Toolkit.getDefaultToolkit().systemClipboard` 剪贴板模式（`LogcatScreen.kt:95-98`、`FileExplorerScreen.kt:123`）。

**Spec:** `docs/superpowers/specs/2026-08-14-adb-gui-design.md`。

## Global Constraints

- 同第一波：desktop 无 Compose UI 测试框架，每个任务以 `./gradlew :desktop:compileKotlin` 编译验证 + 手动运行验证为准，无单测。
- i18n 新 key 在 `Strings.kt` zh（line 18 起）+ en（line 290 起）各加一条。
- 复用现有 `copy` / `connect_first_device` / `no_device_hint` 等 key，不重复定义。
- 每任务一提交，Conventional Commits。
- 分支 `feat/ui-usability-polish-2`。

---

### Task E1: DeviceInfo 属性行加复制按钮

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceInfoScreen.kt:156-169`

**改动说明：** 每个 `PropRow` 行尾加一个 `IconButton`（ContentCopy 图标），点击复制 value 到剪贴板，图标短暂切换为 Check 1.5s 作为反馈。serial / androidVersion / sdkInt 等是开发/测试高频要复制的值。

- [ ] **Step 1: 加 import**

```kotlin
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
```

- [ ] **Step 2: 改 PropRow 加复制按钮**

把 `DeviceInfoScreen.kt:156-169` 的 `PropRow` 改为：
```kotlin
@Composable
private fun PropRow(label: String, value: String) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.width(160.dp),
        )
        Text(value, style = MaterialTheme.typography.body1, modifier = Modifier.weight(1f))
        IconButton(onClick = {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
            copied = true
        }) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = Strings.t("copy"),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
```

- [ ] **Step 3: 编译 + 验证 + 提交**

Run: `./gradlew :desktop:compileKotlin`（通过）→ `:desktop:run`：Device Info 页每行右侧有复制图标，点 serial 行的图标 → 图标变 ✓ 1.5s，粘贴验证剪贴板是序列号。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceInfoScreen.kt
git commit -m "feat(desktop): copy-to-clipboard on device info prop rows"
```

---

### Task E2: AppConsole 包名行加复制按钮

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt:223-244`（`PackageSelectRow`）

**改动说明：** 包列表行的包名是用户常要复制的（去命令行/搜索用）。行尾加一个 ContentCopy IconButton，复制包名。行点击仍选中包，复制按钮独立不冲突。

- [ ] **Step 1: 加 import**

`AppConsoleScreen.kt` 已 import `IconButton`（用于？实际未 import——需加）。补：
```kotlin
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.layout.size
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
```
（`size` 未 import——`AppConsoleScreen` 当前无 size import，需加。）

- [ ] **Step 2: PackageSelectRow 加复制按钮**

把 `PackageSelectRow`（`AppConsoleScreen.kt:223-244`）改为：
```kotlin
@Composable
private fun PackageSelectRow(
    pkg: PackageInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pkg.name, style = MaterialTheme.typography.body1)
            if (pkg.isSystem) {
                Text(Strings.t("system"), style = MaterialTheme.typography.caption)
            }
        }
        IconButton(onClick = {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(pkg.name), null)
        }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = Strings.t("copy"), modifier = Modifier.size(18.dp))
        }
    }
}
```

- [ ] **Step 3: 编译 + 验证 + 提交**

Run: `./gradlew :desktop:compileKotlin` → `:desktop:run`：App Console 包列表每行右侧有复制图标，点击复制包名，粘贴验证。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt
git commit -m "feat(desktop): copy package name button in app console"
```

---

### Task E3: 功能页空状态加"连接设备"按钮

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/ShellScreen.kt:38-41`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemOpsScreen.kt:41-45`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerScreen.kt:46-50`

**改动说明：** 这三页 `selectedSerial == null` 时只显示一行 `no_device_selected` 文案，没有引导动作。改为用 `EmptyState`（带 `connect_first_device` 主按钮）——但需要能打开 Connect 对话框。这三页的 `onOpenConnect` 回调要接到 AppShell 的 `showConnect`。因此给三个 composable 各加一个 `onOpenConnect: () -> Unit = {}` 参数，AppShell 调用处传入 `{ showConnect = true }`。

- [ ] **Step 1: ShellScreen 加参数 + EmptyState**

`ShellScreen.kt` 签名加 `onOpenConnect: () -> Unit = {}`，空状态分支改为 `EmptyState(title = Strings.t("no_device_selected"), hint = Strings.t("no_device_hint"), actionLabel = Strings.t("connect_first_device"), onAction = onOpenConnect)`。补 import：
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
```
（`EmptyState`、`Button` 等同包可用，无需 import。）

把 `if (selectedSerial == null) { Box(...) { Text(no_device_selected) } } else { Column{...} }` 改为：
```kotlin
if (selectedSerial == null) {
    EmptyState(
        title = Strings.t("no_device_selected"),
        hint = Strings.t("no_device_hint"),
        icon = Icons.Filled.Devices,
        actionLabel = Strings.t("connect_first_device"),
        onAction = onOpenConnect,
    )
} else {
    Column(...) { /* 原内容 */ }
}
```
（保留 `else` 分支原内容不变。）

- [ ] **Step 2: SystemOpsScreen 同样改**

`SystemOpsScreen.kt` 加 `onOpenConnect: () -> Unit = {}` 参数，空状态分支用 `EmptyState`（icon = `Icons.Filled.PowerSettingsNew` 已 import）。补 import `EmptyState`（同包，无需）。

- [ ] **Step 3: FileExplorerScreen 同样改**

`FileExplorerScreen.kt` 加 `onOpenConnect: () -> Unit = {}` 参数，空状态用 `EmptyState`（icon = `Icons.Filled.FolderOpen` 已 import）。

- [ ] **Step 4: AppShell 调用处传 onOpenConnect**

`AppShell.kt` 三处调用：
- `ShellScreen` 当前未在 AppShell 直接调用（通过 `onOpenShell`）——**跳过 ShellScreen 的 AppShell 改动**（它不在 AppShell 的 when 分支里，是 DeviceOverviewScreen 内的 onOpenShell 按钮触发的独立流程）。实际 ShellScreen 只在 DeviceOverviewScreen 用 `onOpenShell` 回调，不是独立页。重新确认：AppShell.kt 的 when 分支里没有 ShellScreen，ShellScreen 是独立窗口/流程。**因此 ShellScreen 的空状态加连接按钮意义不大（用户不会停留在 Shell 页空状态）——从本任务移除 ShellScreen，只改 SystemOps + FileExplorer。**

修正：
- SystemOpsScreen：AppShell.kt:143-144 调用，加 `onOpenConnect = { showConnect = true }`。
- FileExplorerScreen：AppShell.kt:149-150 调用，加 `onOpenConnect = { showConnect = true }`。
- ShellScreen：不改（不在 AppShell when 分支）。

- [ ] **Step 5: 编译 + 验证 + 提交**

Run: `./gradlew :desktop:compileKotlin` → `:desktop:run`：未选设备时切到 System Ops / File Explorer 页，显示引导卡片，点"连接设备"开 Connect 对话框。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/SystemOpsScreen.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/FileExplorerScreen.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt
git commit -m "feat(desktop): empty-state connect button on system ops & file explorer pages"
```

---

### Task E4: 侧栏 NavItem 键盘可达 + 焦点环

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt:177-212`

**改动说明：** `NavItem` 的 `Box.clickable` 已让元素可聚焦（Tab 能进），但需显式 Enter/Space 激活 + 可见焦点环。用 `MutableInteractionSource` 观察焦点态，聚焦时加 1.5dp primary 边框；`onPreviewKeyEvent` 处理 Enter/NumPadEnter/SpaceBar → onClick。

- [ ] **Step 1: 加 import**

`AppShell.kt` 补：
```kotlin
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.LocalIndication
```

- [ ] **Step 2: NavItem 加焦点环 + 键盘激活**

把 `NavItem` 的 `Box(modifier = modifier.fillMaxWidth().height(44.dp).background(backgroundColor).clickable(onClick = onClick))` 改为：
```kotlin
val interactionSource = remember { MutableInteractionSource() }
val focused by interactionSource.collectIsFocusedAsState()
val focusBorder = if (focused) {
    Modifier.border(1.5.dp, MaterialTheme.colors.primary)
} else Modifier
Box(
    modifier = modifier
        .fillMaxWidth()
        .height(44.dp)
        .background(backgroundColor)
        .then(focusBorder)
        .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
        .onPreviewKeyEvent { e ->
            if (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.SpaceBar) {
                onClick(); true
            } else false
        },
) {
    /* 原内容不变 */
}
```

- [ ] **Step 3: 编译 + 验证 + 提交**

Run: `./gradlew :desktop:compileKotlin` → `:desktop:run`：Tab 键能聚焦侧栏导航项（出现 primary 边框），按 Enter/Space 切换页面。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt
git commit -m "feat(desktop): keyboard focus + activation for sidebar nav items"
```

---

### Task E5: 硬编码字符图标化

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt:188-192`（▶/▼）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/RemoteScreen.kt:52-58`（↑↓←→）

**改动说明：** `▶/▼` 和 `↑↓←→` 是字符当图标，在 Compose Monospace 下可能渲染成 tofu 且不可控（项目 memory: 控制字符 tofu 风险）。换成 Material 矢量图标。`ArrowDropDown`/`ArrowDropUp` 替代 ▼/▶（展开/折叠更贴切）；`KeyboardArrowUp/Down/Left/Right` 替代 ↑↓←→，"OK" 文字保留。

- [ ] **Step 1: AppConsole 高级区展开图标**

`AppConsoleScreen.kt` 加 import：
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
```
把 `:190` 的：
```kotlin
Text(if (advancedOpen) "▼ ${Strings.t("advanced_ops")}" else "▶ ${Strings.t("advanced_ops")}")
```
改为：
```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(if (advancedOpen) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown, contentDescription = null)
    Spacer(Modifier.width(4.dp))
    Text(Strings.t("advanced_ops"))
}
```
（`Icon`、`Row`、`Spacer`、`width` 已 import。）

- [ ] **Step 2: Remote D-pad 图标化**

`RemoteScreen.kt` 加 import：
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
```
把 `:52-58` 的 D-pad 4 个方向按钮 `Text("↑")` / `Text("←")` / `Text("→")` / `Text("↓")` 分别换为 `Icon(Icons.Filled.KeyboardArrowUp, contentDescription = Strings.t(...))` 等。"OK" 按钮保留 `Text("OK")`。

为 contentDescription 复用现有 i18n key 若有（`back`/`home` 等不贴切）。方向键无现成 key——加 4 个 i18n key 更规范，但为控制范围，用 `contentDescription = null`（方向键语义由位置直观，且 D-pad 视觉本身已表意，符合「decorative icon beside... 」豁免）。采用 `contentDescription = null`。

- [ ] **Step 3: 编译 + 验证 + 提交**

Run: `./gradlew :desktop:compileKotlin` → `:desktop:run`：App Console 高级区按钮显示下拉/上拉箭头图标；Remote D-pad 显示方向箭头，无 tofu。

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/AppConsoleScreen.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/RemoteScreen.kt
git commit -m "refactor(desktop): replace glyph pseudo-icons with vector icons"
```

---

## Self-Review

1. **覆盖**：四组（复制 / 空状态连接 / 键盘导航 / 字符图标）均有任务。ShellScreen 经 Step 4 修正后从 E3 移除（不在 AppShell when 分支，空状态不会停留）。
2. **占位**：无 TBD，代码片段完整。
3. **一致性**：`onOpenConnect` 参数名在 SystemOps/FileExplorer/AppShell 调用一致；`copy` i18n key 复用既有；剪贴板模式与 Logcat/FileExplorer 既有用法一致。
4. **风险**：E4 焦点环可能与选中态背景叠加视觉噪点——手动验证时确认；若过重改为左侧指示条加粗。E5 的 `contentDescription = null` 对方向键符合豁免（视觉自明）。
