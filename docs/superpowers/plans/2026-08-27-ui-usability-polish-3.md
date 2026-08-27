# UI 易用性打磨 第三波 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox tracking.

**Goal:** 补齐 7 条小而高信号的易用性缺口：空 IP 禁用、scrcpy 数值校验、删除按钮语义、空列表双语义、截图重试、SystemInfo 复制常驻、三页 busy spinner。

**Architecture:** 全部 `:desktop` Compose 交互层。`ScreenshotViewModel.capture()` 已是 public（`Main.kt:93` 调用），第三波复用、不改 VM 签名。

**Spec:** `docs/superpowers/specs/2026-08-14-adb-gui-design.md`。分支 `feat/ui-usability-polish-3`。

## Global Constraints

- 同前两波：无 Compose UI 测试框架，每任务编译验证 + 手动运行验证，无单测。i18n 在 zh（line 18 起）+ en（line 290 起）各加。每任务一提交。
- 大改项（3 全局快捷键 / 23 文件对话框统一 / 14 PairDialog 端口自动填 / 6 错误持久化策略 / 18 Logcat 工具栏层级 / 20 scrcpy sticky / 27 Settings Browse-Apply / 10 Settings clear 确认 / 26 monkey 注释）不在本波。

---

### Task F1 (item 15): ConnectDialog 空 IP 禁用 Connect，移除静默 fallback

**Files:** `desktop/.../ui/ConnectDialog.kt:48-49,85-91`

- [ ] 把 `submitConnect` 的 `vm.connect(ip.ifBlank { "127.0.0.1" }, p)` 改为 `vm.connect(ip, p)`（移除 fallback）。
- [ ] Button 的 `enabled` 改为 `!busy && ip.isNotBlank() && port.isNotBlank()`。
- [ ] 编译 + 手动验证：IP 留空时 Connect 置灰，无法触发；填入才可用。
- [ ] 提交：`feat(desktop): ConnectDialog disable connect on blank IP instead of silent fallback`

### Task F2 (item 21): scrcpy max-size/max-fps 范围校验

**Files:** `desktop/.../ui/DeviceOverviewScreen.kt:189-205,233-294`

- [ ] 在 Start 按钮上方计算校验态：
```kotlin
val maxSizeN = optMaxSize.value.toIntOrNull()
val maxFpsN = optMaxFps.value.toIntOrNull()
val maxSizeErr = maxSizeN != null && maxSizeN != 0 && (maxSizeN < 16 || maxSizeN > 8192)
val maxFpsErr = maxFpsN != null && maxFpsN != 0 && (maxFpsN < 1 || maxFpsN > 120)
```
- [ ] 两个 OutlinedTextField 加 `isError = maxSizeErr` / `maxFpsErr`。
- [ ] Start `enabled` 追加 `&& !maxSizeErr && !maxFpsErr`。
- [ ] i18n: `scrcpy_max_size_err` zh="尺寸需为 0 或 16–8192" en="Size must be 0 or 16–8192"；`scrcpy_max_fps_err` zh="帧率需为 0 或 1–120" en="FPS must be 0 or 1–120"。字段下方 `if (maxSizeErr) Text(Strings.t("scrcpy_max_size_err"), ...)`。
- [ ] 编译 + 手动验证：输 999999 → 字段标红 + 提示 + Start 置灰；输 0 或合法值正常。
- [ ] 提交：`feat(desktop): validate scrcpy max-size/max-fps input ranges`

### Task F3 (item 25): Remote 删除确认按钮 ok→remove

**Files:** `desktop/.../ui/RemoteScreen.kt:151`

- [ ] 把 `confirmButton = { TextButton(...) { Text(Strings.t("ok")) } }` 改为 `Text(Strings.t("remove"))`。
- [ ] 编译 + 手动验证：删除自定义按钮对话框确认按钮文案为"移除"，与标题一致。
- [ ] 提交：`fix(desktop): Remote delete confirm button label matches title`

### Task F4 (item 13): AppConsole no_packages 拆双语义

**Files:** `desktop/.../ui/AppConsoleScreen.kt:217,242` + `i18n/Strings.kt`

- [ ] i18n：新增 `no_package_selected` zh="未选择应用" en="No app selected"。`no_packages` 保留表示列表空。
- [ ] `:242`（未选包分支）的 `Strings.t("no_packages")` 改为 `Strings.t("no_package_selected")`。
- [ ] 编译 + 手动验证：列表空显示"暂无应用"，未选包显示"未选择应用"。
- [ ] 提交：`fix(desktop): distinguish empty package list from no selection`

### Task F5 (item 16): Screenshot 重新截取按钮

**Files:** `desktop/.../ui/ScreenshotScreen.kt`

- [ ] 加 `val captureDone by vm.captureDone.collectAsState()` + `var capturing by remember { mutableStateOf(false) }`。
- [ ] `LaunchedEffect(captureDone) { if (capturing) capturing = false }`。
- [ ] i18n：`screenshot_recapture` zh="重新截取" en="Recapture"。
- [ ] 底部 action Row 最左加 `OutlinedButton(enabled = !capturing, onClick = { capturing = true; vm.capture() }) { if (capturing) { CircularProgressIndicator(18dp) } else Text(Strings.t("screenshot_recapture")) }`。需 import `CircularProgressIndicator`、`size`。
- [ ] 编译 + 手动验证：截图窗口点"重新截取" → spinner → 完成后图像刷新。
- [ ] 提交：`feat(desktop): recapture button in screenshot window`

### Task F6 (item 19): SystemInfo Copy/Save 常驻禁用

**Files:** `desktop/.../ui/SystemInfoScreen.kt:130-152`

- [ ] 移除 `if (result != null) { ... }` 包裹，两个 OutlinedButton 常驻；`enabled = result != null`。onClick 内用 `val r = result ?: return@OutlinedButton` 后用 `r` 替换 `result!!`（消除 `!!`）。
- [ ] 编译 + 手动验证：进 System Info 未跑命令时 Copy/Save 置灰可见；跑完可用。
- [ ] 提交：`feat(desktop): SystemInfo copy/save always visible, disabled when no result`

### Task F7 (item 4): SystemOps/Remote/Shell busy spinner

**Files:** `desktop/.../ui/SystemOpsScreen.kt`、`RemoteScreen.kt`、`ShellScreen.kt`

- [ ] SystemOpsScreen：`busy` 时在标题行末显示 `CircularProgressIndicator(18dp)`（与 reboot Row 同级或标题旁）。import `size`。
- [ ] RemoteScreen：`busy` 时在 D-pad 上方或标题旁显示 18dp spinner。import `CircularProgressIndicator`、`size`（material.* 已有 Icon 但 CPI 需显式或经 material.*；material.* 含 CircularProgressIndicator，无需额外 import；size 需加）。
- [ ] ShellScreen：该页无 busy（fire-and-forget），跳过——不加无意义的 spinner（避免死代码）。
- [ ] 编译 + 手动验证：System Ops 点 reboot 确认后标题旁出现 spinner 直到完成；Remote 发送按键时 D-pad 旁 spinner。
- [ ] 提交：`feat(desktop): busy spinner on system ops & remote during async ops`

---

## Self-Review

- 覆盖 7 条选定项。ShellScreen 在 F7 跳过（无 busy，不加无意义 spinner，符合技术债 §2）。F5 复用已有 `capture()`，无 VM 改动。F6 消除 `result!!`。i18n 新 key：`scrcpy_max_size_err`/`scrcpy_max_fps_err`/`no_package_selected`/`screenshot_recapture` 共 4 组双语。
- 风险：F2 范围阈值（8192/120）为保守上限，scrcpy 实际接受更大值——取上限足够防 999999 这类误输，不阻塞合法用法。手动验证确认。
