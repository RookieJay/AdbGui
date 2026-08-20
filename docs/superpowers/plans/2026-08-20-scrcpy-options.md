# scrcpy 启动选项面板 + 快捷键提示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the minimal Start/Stop scrcpy row in Device Overview with a launch-options panel (existing 4 options + 4 new toggles) and a keyboard-shortcuts help Dialog. EMBEDDED radio stays disabled (placeholder). EXTERNAL-only for now.

**Classification:** Bounded — extends existing scrcpy flow (ScrcpyOptions / ScrcpyLauncher / DeviceOverviewScreen already exist). No interface others depend on changes; ScrcpyOptions field additions are additive.

**Spec / context:** `docs/superpowers/plans/2026-08-20-scrcpy.md` (parent scrcpy plan, T1-T5 done). This plan = follow-up to Task 5 (UI) + companion to the deferred Task 6 (Settings page). Shortcut content sourced from `scrcpy.exe --help` (scrcpy 4.1, already bundled).

## Global Constraints

- **JDK 21**; Gradle wrapper 8.11 (Tencent `distributionUrl` + Aliyun Maven mirrors configured; keep them).
- **`:core` no UI deps.** ScrcpyOptions stays a pure data class in `:core/domain`.
- **UI red line #2:** UI only calls platform interfaces; never touches scrcpy process directly.
- **Logic/execution separation:** CLI arg-building extracted into a pure `ScrcpyArgsBuilder` (testable), Launcher calls it.
- **i18n:** all UI strings via `Strings.t(...)` (zh + en).
- **SelectableText:** error text blocks use `SelectableText(...)`.
- **Conventional Commits:** `feat(desktop):` / `feat(core):`.
- **TDD** for `ScrcpyArgsBuilder` (write failing test → impl → green → commit). `:core` data class needs no test (no logic).
- **No dead code:** EMBEDDED branch in Launcher is NOT dead code (it's a reserved, disabled UI affordance) — keep. Do not add unused params/classes.

## scrcpy CLI flag map (verified from `scrcpy.exe --help`, v4.1)

| ScrcpyOptions field | flag | notes |
|---|---|---|
| maxSize > 0 | `--max-size N` | 0 = native |
| stayAwake | `--stay-awake` | |
| turnScreenOff | `--turn-screen-off` | |
| recordPath != null | `--record PATH` | skip if blank |
| alwaysOnTop | `--always-on-top` | NEW |
| fullscreen | `--fullscreen` | NEW |
| maxFps > 0 | `--max-fps N` | NEW, 0 = unset |
| noAudio | `--no-audio` | NEW |

Order in args: `scrcpyPath -s serial` then flags (order irrelevant to scrcpy).

## scrcpy MOD+key shortcuts (for Dialog, from `scrcpy --help`)

MOD = Left Alt (default) or Left Super. Common:
- MOD+b → 返回 / Back (also turns screen on if off)
- MOD+h → Home
- MOD+s → 最近任务 / App switch
- MOD+n → 通知面板 / Notifications
- MOD+Shift+n → 快捷设置 / Quick settings
- MOD+o → 息屏 / Turn screen off (MOD+Shift+o to wake)
- MOD+f → 全屏 / Fullscreen
- MOD+i → FPS / Framerate log
- MOD+m → 置顶切换 / Always-on-top toggle
- MOD+p → 电源 / Power
- MOD+↑/↓ → 音量 / Volume
- MOD+c/v/x → 复制/粘贴/剪切剪贴板
- 右键 → 返回 / Right-click = Back
- 中键 → Home

(Keep the list curated — ~12 rows, the most useful. Descriptions localized; the MOD+key part is universal.)

---

## File Structure

```
:core
└─ domain/ScrcpyOptions.kt        + alwaysOnTop, fullscreen, maxFps, noAudio
:desktop
├─ platform/ScrcpyArgsBuilder.kt  NEW — pure object: build(path, serial, options): List<String>
├─ platform/ScrcpyArgsBuilderTest.kt  NEW (test, :desktop:test)
├─ platform/ScrcpyLauncher.kt     Launcher.open() now calls ScrcpyArgsBuilder
├─ ui/DeviceOverviewScreen.kt     scrcpy section → options panel + shortcuts Dialog
└─ ui/i18n/Strings.kt             + option keys + shortcut desc keys (zh + en)
```

---

## Task 1: ScrcpyOptions fields + ScrcpyArgsBuilder (TDD)

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/domain/ScrcpyOptions.kt`
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/platform/ScrcpyArgsBuilder.kt`
- Create: `desktop/src/test/kotlin/com/adbgui/desktop/platform/ScrcpyArgsBuilderTest.kt`

**Step 1 — ScrcpyOptions:** add fields (keep existing):
```kotlin
data class ScrcpyOptions(
    val maxSize: Int = 0,
    val stayAwake: Boolean = true,
    val turnScreenOff: Boolean = false,
    val recordPath: String? = null,
    val alwaysOnTop: Boolean = false,
    val fullscreen: Boolean = false,
    val maxFps: Int = 0,
    val noAudio: Boolean = false,
)
```

- [ ] **Step 2 — Write failing test** (`ScrcpyArgsBuilderTest.kt`):
  - all flags on → args contains `-s serial`, `--max-size 1920`, `--stay-awake`, `--turn-screen-off`, `--record /p.mp4`, `--always-on-top`, `--fullscreen`, `--max-fps 60`, `--no-audio`, and starts with the scrcpy path.
  - all defaults (empty options) → only `-s serial` (no flags; stayAwake default true → `--stay-awake` present; test with stayAwake=false to assert absence).
  - `maxSize=0`/`maxFps=0` → no `--max-size`/`--max-fps`.
  - `recordPath=null` or blank → no `--record`.

- [ ] **Step 3 — Implement `ScrcpyArgsBuilder`** (pure object, no I/O):
```kotlin
package com.adbgui.desktop.platform
import com.adbgui.core.domain.ScrcpyOptions
object ScrcpyArgsBuilder {
    fun build(scrcpyPath: String, serial: String, options: ScrcpyOptions): List<String> = buildList {
        add(scrcpyPath)
        add("-s"); add(serial)
        if (options.maxSize > 0) { add("--max-size"); add(options.maxSize.toString()) }
        if (options.stayAwake) add("--stay-awake")
        if (options.turnScreenOff) add("--turn-screen-off")
        if (!options.recordPath.isNullOrBlank()) { add("--record"); add(options.recordPath) }
        if (options.alwaysOnTop) add("--always-on-top")
        if (options.fullscreen) add("--fullscreen")
        if (options.maxFps > 0) { add("--max-fps"); add(options.maxFps.toString()) }
        if (options.noAudio) add("--no-audio")
    }
}
```

- [ ] **Step 4 — Verify:** `./gradlew :desktop:test --tests "*ScrcpyArgsBuilderTest"` green.
- [ ] **Step 5 — Commit:** `feat(core): add scrcpy launch options + ScrcpyArgsBuilder` (ScrcpyOptions is :core; builder is :desktop — split commits if desired: `feat(core): add ScrcpyOptions launch fields` then `feat(desktop): add ScrcpyArgsBuilder with tests`).

---

## Task 2: Launcher uses ScrcpyArgsBuilder

**Files:** Modify `desktop/src/main/kotlin/com/adbgui/desktop/platform/ScrcpyLauncher.kt`

- [ ] **Step 1:** Replace inline `buildList` in `open()` with `val args = ScrcpyArgsBuilder.build(scrcpyPath, serial, options)`. Keep ProcessBuilder + EMBEDDED SetParent logic untouched. The EMBEDDED branch is reserved (disabled in UI) — leave as-is, NOT dead code (documented placeholder for future work).

- [ ] **Step 2 — Compile:** `./gradlew :desktop:compileKotlin`.
- [ ] **Step 3 — Commit:** `refactor(desktop): ScrcpyLauncher delegates arg building to ScrcpyArgsBuilder`.

---

## Task 3: i18n keys

**Files:** Modify `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt` (both zh + en maps)

- [ ] **Step 1 — Add option keys:**
  - `scrcpy_always_on_top` → 置顶 / Always on top
  - `scrcpy_fullscreen` → 全屏 / Fullscreen
  - `scrcpy_max_fps` → 最大帧率 / Max FPS
  - `scrcpy_no_audio` → 关音频 / No audio
  - `scrcpy_shortcuts` → 键盘快捷键 / Keyboard shortcuts
  - `scrcpy_shortcuts_hint` → "MOD = 左 Alt 键" / "MOD = Left Alt key"
  - One key per shortcut row description (≈12 keys): `scrcpy_sc_back`, `scrcpy_sc_home`, `scrcpy_sc_appswitch`, `scrcpy_sc_notifications`, `scrcpy_sc_quicksettings`, `scrcpy_sc_screenoff`, `scrcpy_sc_fullscreen`, `scrcpy_sc_fps`, `scrcpy_sc_alwaysontop`, `scrcpy_sc_power`, `scrcpy_sc_volume`, `scrcpy_sc_clipboard`, `scrcpy_sc_rightclick_back`, `scrcpy_sc_midclick_home`.

- [ ] **Step 2 — Compile + commit:** `feat(desktop): i18n for scrcpy options + shortcuts`.

---

## Task 4: Device Overview scrcpy panel + shortcuts Dialog

**Files:** Modify `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt`

- [ ] **Step 1 — Add options state** (session state via `remember`):
```kotlin
val opts = remember { mutableStateOf(ScrcpyOptions()) }  // edit fields via copy
val showShortcuts = remember { mutableStateOf(false) }
```

- [ ] **Step 2 — Render options panel** (below the disabled EMBEDDED radios, above the buttons). Checkboxes for: 置顶, 全屏, 保持唤醒, 息屏, 关音频. Number/text fields for: 分辨率限制, 最大帧率, 录制路径. Bind to `opts.value.copy(...)`.

- [ ] **Step 3 — Buttons row:** [开始投屏] [停止投屏] [键盘快捷键]. Start builds `opts.value` and calls `scrcpyLauncher.open(path, serial, opts.value, ScrcpyMode.EXTERNAL)`. Shortcuts button sets `showShortcuts.value = true`.

- [ ] **Step 4 — Shortcuts Dialog:** `if (showShortcuts.value) AlertDialog(onDismissRequest={showShortcuts.value=false}, title={Text(Strings.t("scrcpy_shortcuts"))}, text={ Column { Text(Strings.t("scrcpy_shortcuts_hint")); shortcutsTable.forEach { Row { Text(it.first); Spacer; Text(Strings.t(it.second)) } } } }, confirmButton={ Button({showShortcuts.value=false}){Text(Strings.t("ok"))} })`. Define `shortcutsTable` as a local list of (MOD key, i18n key id) pairs.

- [ ] **Step 5 — Compile + smoke:** `./gradlew :desktop:compileKotlin` then `./gradlew :desktop:run` — verify panel renders, toggles work, shortcuts Dialog opens, Start launches scrcpy with chosen flags (check via scrcpy window behavior: always-on-top window stays on top, etc.). Kill app after.

- [ ] **Step 6 — Commit:** `feat(desktop): scrcpy launch-options panel + keyboard-shortcuts dialog`.

---

## Self-Review

**1. Spec coverage:** 4 new options → T1 (model) + T2 (args) + T4 (UI). Shortcuts Dialog → T3 (i18n) + T4 (UI). EMBEDDED placeholder preserved (disabled). EXTERNAL-only honored. ✅
**2. Placeholder scan:** No TBD/TODO. EMBEDDED branch kept with comment = intentional placeholder, not dead code. ✅
**3. Type consistency:** `ScrcpyOptions` fields flow T1→T2(builder)→T4(UI copy). `ScrcpyArgsBuilder.build(path, serial, options)` T1→T2. i18n keys T3→T4. ✅
**4. TDD:** ScrcpyArgsBuilder has failing test → impl → green before T2/T4. ✅
**5. Red lines:** :core only gains data class fields (no UI/process). UI only touches ScrcpyLauncher interface. Logic (args) separated from execution (ProcessBuilder). ✅

No gaps. Plan complete.

---

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-08-20-scrcpy-options.md`. Open a fresh Claude window and run:

> 读 `docs/superpowers/plans/2026-08-20-scrcpy-options.md`,按 CLAUDE.md 约定执行(TDD + 每任务一个提交)。

Two execution options: (1) Subagent-Driven (fresh subagent per task, review between) — recommended; (2) Inline in one session.
