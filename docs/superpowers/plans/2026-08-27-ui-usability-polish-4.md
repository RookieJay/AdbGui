# UI 易用性打磨 第四波 Implementation Plan

> REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox tracking.

**Goal:** 三条剩余小项——Logcat 工具栏层级化、scrcpy Start 上移、Remote 错误持久化对齐。

**Architecture:** 全部 `:desktop` Compose 交互层，无 `:core` 改动。分支 `feat/ui-usability-polish-4`。

## Global Constraints
- 无 Compose UI 测试框架；每任务编译验证 + 手动运行。i18n 双语。每任务一提交。
- 大改项（3 全局快捷键 / 23 文件对话框统一）不在本波。14 PairDialog 端口自动填经核实不可行（adb pair 输出只含配对端口，连接端口仅在设备屏幕显示，无 adb 输出可解析）——标注为已查证、不实现。

---

### Task G1 (item 18): Logcat 工具栏层级化

**Files:** `desktop/.../ui/LogcatScreen.kt:48-99`

- [ ] 把 Pause/Clear/Copy 三个 `OutlinedButton` 改为 `IconButton`（图标按钮），Export 保持 `Button` 主操作（文本+Download 图标）。Clear 仍触发 wave-1 的 `confirmClear`。
- [ ] 加 import：`Icons`, `PlayArrow`, `Pause`, `Delete`, `ContentCopy`, `Download`（material.* 已给 IconButton）。
- [ ] 编译 + 手动验证：工具栏只剩 Level + 搜索 + 主操作 Export + 三个图标按钮，视觉清爽。
- [ ] 提交：`refactor(desktop): Logcat toolbar hierarchy — icon buttons + primary export`

### Task G2 (item 20): scrcpy Start/Stop 上移

**Files:** `desktop/.../ui/DeviceOverviewScreen.kt`（scrcpy installed 分支）

- [ ] 把"--- Buttons row ---"（Start/Stop/Shortcuts）从 scrcpy installed 块的底部移到 mode toggle 之前（status/error 之后），使进入页面即可见 Start，不必滚动。
- [ ] 编译 + 手动验证：进 Device Overview，scrcpy Start 在选项之上、立即可见。
- [ ] 提交：`feat(desktop): move scrcpy Start/Stop above options for immediate access`

### Task G3 (item 6): Remote 错误持久化对齐

**Files:** `desktop/.../ui/RemoteScreen.kt:115-121`

- [ ] 移除 `LaunchedEffect(msg) { delay(3000); vm.clearError() }`（3s 自动清，与其他页不一致）。
- [ ] 给 `InlineMessageBanner` 加 `onDismiss = { vm.clearError() }`（与 DeviceListPane 一致：持久 + 可手动关）。
- [ ] 编译 + 手动验证：Remote 出错后 banner 常驻直到手动 × 或下一次操作。
- [ ] 提交：`fix(desktop): Remote error banner persists + dismissible like other pages`

## Self-Review
- G1 保留 wave-1 的 Clear 确认流（`confirmClear`），只换呈现。G2 是块内顺序调整，不改逻辑。G3 删 3s 自动清 + 加 onDismiss，与 DeviceListPane `:112` 一致。
