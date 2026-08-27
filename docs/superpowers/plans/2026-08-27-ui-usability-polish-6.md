# UI 易用性打磨 第六波 — Device Overview 信息架构重构

> REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox tracking.

**Goal:** 重排 Device Overview：DeviceInfo 可折叠、设备工具（含 reboot）上移、删独立 System Ops 页。

**Architecture:** 纯 `:desktop` Compose 层。reboot 合并进 DeviceOverview 的工具行（复用 `systemOpsVm`）。分支 `feat/ui-usability-polish-6`。

## Global Constraints
- 编译验证 + 手动运行。i18n 复用现有 `reboot*`/`device_tools`/`root_op`/`remount_op`/`open_shell` key，无新 key。每任务一提交。

---

### Task I1: DeviceInfoScreen 可折叠

**Files:** `ui/DeviceInfoScreen.kt`

- [ ] 加 `var infoExpanded by remember { mutableStateOf(false) }`；import `Icons`/`KeyboardArrowDown`/`KeyboardArrowUp`/`clickable`。
- [ ] props 非空时：用一行可点击摘要 `"$brand $model · Android $androidVersion · $serial"` + 箭头图标替代常驻 8 行；展开后才显示 8 PropRows。
- [ ] 截图/刷新/导出仍在头部行（高频，不动）。
- [ ] 编译 + 验证 + 提交：`feat(desktop): collapsible device info summary`

### Task I2: DeviceOverview 设备工具上移 + reboot 合并

**Files:** `ui/DeviceOverviewScreen.kt`

- [ ] 把 `if (systemOpsVm != null) {...}` 设备工具块从 Remote 之后移到 DeviceInfo 之后（Remote 之前）。
- [ ] 工具行加第 4 项 reboot：`Box { OutlinedButton("重启") + DropdownMenu(4 个 RebootMode) }`，设 `pendingReboot`；确认 `AlertDialog` 调 `systemOpsVm.reboot(mode)`。import `RebootMode`、`DropdownMenu`/`DropdownMenuItem`。
- [ ] 编译 + 验证 + 提交：`feat(desktop): move device tools up + merge reboot dropdown`

### Task I3: 移除独立 System Ops 导航页

**Files:** `ui/AppShell.kt`

- [ ] 删 `NavPage.SYSTEM_OPS` 枚举项、`navItems` 里的 systemOps spec、when 里的 SYSTEM_OPS 分支。
- [ ] 保留 `systemOpsVm` 参数（仍传给 DeviceOverview）。
- [ ] 编译 + 验证 + 提交：`refactor(desktop): remove standalone system ops nav page`

### Task I4: 删 SystemOpsScreen.kt

**Files:** 删 `ui/SystemOpsScreen.kt`

- [ ] 确认无引用后删除文件。
- [ ] 编译 + 提交：`chore(desktop): drop dead SystemOpsScreen`

## Self-Review
- I2 reboot 确认逻辑从 SystemOpsScreen 搬入 DeviceOverview，复用 systemOpsVm。I3 删 nav，I4 删文件——死代码清理（§2）。折叠默认收起省 ~8 行（§8 progressive-disclosure；§5 content-priority）。
