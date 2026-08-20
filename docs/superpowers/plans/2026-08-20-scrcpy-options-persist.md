# scrcpy 启动选项持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the scrcpy launch options (resolution cap, stay-awake, turn-screen-off, always-on-top, fullscreen, max-fps, no-audio, record folder) to `settings.json` so they survive app restarts; load them back into the Device Overview panel on startup; save the launched profile when the user starts scrcpy.

**Architecture:** A new `@Serializable ScrcpyLaunchProfile` data class in `:core/domain` mirrors the UI option fields (using `recordFolder: String?` instead of scrcpy's per-launch timestamped `recordPath`). It's added to `Settings` and persisted via the existing `SettingsStore` (atomic `.tmp` + `ATOMIC_MOVE`, injected `io` dispatcher). `SettingsViewModel` gains `setScrcpyLaunch(profile)`. `DeviceOverviewScreen` reads the profile from the settings `StateFlow` (re-applied when it changes — i.e. on initial async load and after each save) and writes it back on Start.

**Tech Stack:** Kotlin, kotlinx.serialization (already used by `Settings`), Compose Multiplatform, JUnit + kotlin.test, runTest.

**Spec:** `docs/superpowers/plans/2026-08-20-scrcpy.md` (parent scrcpy plan; this is the deferred "remember my last scrcpy options" follow-up to Task 5/6).

## Global Constraints

- **JDK 21**; Gradle wrapper 8.11 (Tencent `distributionUrl` + Aliyun Maven mirrors; keep them).
- **`:core` no UI deps.** `ScrcpyLaunchProfile` is a pure `@Serializable data class` in `:core/domain`.
- **UI red line #2:** UI reads `DeviceRepository` StateFlow + VMs only. `SettingsViewModel` (wrapping `SettingsStore`) is the existing approved pattern for settings — extend it, do not touch `SettingsStore` from the UI directly.
- **`:core` TDD:** failing test → impl → green → commit. `:desktop` VM tests mirror the `SettingsViewModelTest` pattern (injected `io = Dispatchers.Unconfined`, `advanceUntilIdle`).
- **Conventional Commits:** `feat(core):` / `feat(desktop):`.
- **No dead code:** do not add unused fields/params. `ScrcpyLaunchProfile.recordFolder` semantics = the folder to record into (null/blank = no recording); the per-launch timestamped filename is still built at Start in `DeviceOverviewScreen`.
- **Atomic write + injected dispatcher** already live in `SettingsStore` — reuse, don't re-implement.

## Mapping (UI state ↔ profile)

| `DeviceOverviewScreen` opt state | `ScrcpyLaunchProfile` field |
|---|---|
| `optMaxSize` (String → Int) | `maxSize: Int = 0` |
| `optStayAwake` | `stayAwake: Boolean = true` |
| `optTurnScreenOff` | `turnScreenOff: Boolean = false` |
| `optAlwaysOnTop` | `alwaysOnTop: Boolean = false` |
| `optFullscreen` | `fullscreen: Boolean = false` |
| `optMaxFps` (String → Int) | `maxFps: Int = 0` |
| `optNoAudio` | `noAudio: Boolean = false` |
| `optRecord` + `optRecordPath` (folder) | `recordFolder: String? = null` (null/blank = no record) |

Defaults mirror `ScrcpyOptions` defaults (`stayAwake = true`, rest false/zero).

---

## File Structure

```
:core
├─ domain/ScrcpyLaunchProfile.kt        NEW — @Serializable data class
└─ settings/Settings.kt                 + scrcpyLaunch field
   (settings/SettingsStore.kt           unchanged — already generic)
:desktop
├─ ui/SettingsViewModel.kt              + setScrcpyLaunch(profile)
├─ ui/DeviceOverviewScreen.kt           load profile on change; save on Start
└─ ui/AppShell.kt                       pass settingsVm through to DeviceOverviewScreen
:core/test
└─ settings/SettingsStoreTest.kt        + roundtrip test for scrcpyLaunch
:desktop/test
└─ ui/SettingsViewModelTest.kt         + setScrcpyLaunch_persists test
```

---

## Task 1: ScrcpyLaunchProfile model + Settings field (TDD)

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/domain/ScrcpyLaunchProfile.kt`
- Modify: `core/src/main/kotlin/com/adbgui/core/settings/Settings.kt` (add one field)
- Test: `core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt` (add 2 tests)

**Interfaces:**
- Produces: `ScrcpyLaunchProfile(maxSize, stayAwake, turnScreenOff, alwaysOnTop, fullscreen, maxFps, noAudio, recordFolder)` — used by Task 2 (`SettingsViewModel.setScrcpyLaunch`) and Task 3 (`DeviceOverviewScreen` load/save).

- [ ] **Step 1: Write the failing tests** (`SettingsStoreTest.kt`, append inside the class):

```kotlin
@Test
fun scrcpy_launch_defaults_when_no_file() = runTest {
    val s = SettingsStore(tmpDir(), io = kotlinx.coroutines.Dispatchers.Unconfined).load()
    assertEquals(ScrcpyLaunchProfile(), s.scrcpyLaunch)
}

@Test
fun save_then_load_preserves_scrcpy_launch() = runTest {
    val dir = tmpDir()
    val store = SettingsStore(dir)
    val profile = ScrcpyLaunchProfile(
        maxSize = 1920, stayAwake = false, turnScreenOff = true,
        alwaysOnTop = true, fullscreen = true, maxFps = 60, noAudio = true,
        recordFolder = "/rec",
    )
    store.save(Settings(scrcpyLaunch = profile))
    val loaded = SettingsStore(dir).load()
    assertEquals(profile, loaded.scrcpyLaunch)
}
```

Add the import: `import com.adbgui.core.domain.ScrcpyLaunchProfile`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "*SettingsStoreTest" 2>&1 | tail -15`
Expected: FAIL with `Unresolved reference: scrcpyLaunch` (and `ScrcpyLaunchProfile`).

- [ ] **Step 3: Create `ScrcpyLaunchProfile.kt`**

```kotlin
package com.adbgui.core.domain

import kotlinx.serialization.Serializable

/**
 * Persisted scrcpy launch options (user's last-used profile), stored in [com.adbgui.core.settings.Settings].
 * Mirrors [ScrcpyOptions] except [recordFolder] holds the folder to record into (null/blank = no
 * recording); the per-launch timestamped filename is built at launch time in the UI, not persisted.
 * Defaults mirror ScrcpyOptions defaults.
 */
@Serializable
data class ScrcpyLaunchProfile(
    val maxSize: Int = 0,
    val stayAwake: Boolean = true,
    val turnScreenOff: Boolean = false,
    val alwaysOnTop: Boolean = false,
    val fullscreen: Boolean = false,
    val maxFps: Int = 0,
    val noAudio: Boolean = false,
    val recordFolder: String? = null,
)
```

- [ ] **Step 4: Add the field to `Settings`**

In `core/src/main/kotlin/com/adbgui/core/settings/Settings.kt`, add `import com.adbgui.core.domain.ScrcpyLaunchProfile` and one field to the `Settings` data class (after `scrcpyMode`):

```kotlin
    val scrcpyMode: String = "EXTERNAL",  // EMBEDDED / EXTERNAL
    val scrcpyLaunch: ScrcpyLaunchProfile = ScrcpyLaunchProfile(),
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "*SettingsStoreTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, both new tests green. (`encodeDefaults = true` in `SettingsStore.json` ensures the profile is written even when all-default; `ignoreUnknownKeys = true` keeps old settings files loadable.)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/domain/ScrcpyLaunchProfile.kt \
        core/src/main/kotlin/com/adbgui/core/settings/Settings.kt \
        core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt
git commit -m "feat(core): add ScrcpyLaunchProfile persisted to settings"
```

---

## Task 2: SettingsViewModel.setScrcpyLaunch (TDD)

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsViewModel.kt`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `ScrcpyLaunchProfile` (Task 1), `SettingsStore.update { it.copy(scrcpyLaunch = ...) }`.
- Produces: `SettingsViewModel.setScrcpyLaunch(profile: ScrcpyLaunchProfile)` + `settings.value.scrcpyLaunch` (read by Task 3).

- [ ] **Step 1: Write the failing test** (`SettingsViewModelTest.kt`, append inside the class):

```kotlin
@Test
fun setScrcpyLaunch_persists_and_updates_state() = runTest {
    val dir = Files.createTempDirectory("scrcpy")
    val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
    val vm = SettingsViewModel(store, this)
    val profile = ScrcpyLaunchProfile(maxSize = 1280, noAudio = true, recordFolder = "/rec")
    vm.setScrcpyLaunch(profile)
    advanceUntilIdle()
    assertEquals(profile, vm.settings.value.scrcpyLaunch)
    assertEquals(profile, store.load().scrcpyLaunch)
}
```

Add imports: `import com.adbgui.core.domain.ScrcpyLaunchProfile`, `import java.nio.file.Files` (if not present).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :desktop:test --tests "*SettingsViewModelTest.setScrcpyLaunch*" 2>&1 | tail -10`
Expected: FAIL with `Unresolved reference: setScrcpyLaunch`.

- [ ] **Step 3: Implement `setScrcpyLaunch`** in `SettingsViewModel`:

```kotlin
fun setScrcpyLaunch(profile: com.adbgui.core.domain.ScrcpyLaunchProfile) = scope.launch {
    store.update { it.copy(scrcpyLaunch = profile) }
    refresh()
}
```

(Add the import `import com.adbgui.core.domain.ScrcpyLaunchProfile` and use the short name in the signature.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :desktop:test --tests "*SettingsViewModelTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsViewModel.kt \
        desktop/src/test/kotlin/com/adbgui/desktop/ui/SettingsViewModelTest.kt
git commit -m "feat(desktop): SettingsViewModel.setScrcpyLaunch persists launch profile"
```

---

## Task 3: DeviceOverviewScreen loads + saves the profile

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt` (pass `settingsVm` through)

**Interfaces:**
- Consumes: `ScrcpyLaunchProfile` (Task 1), `SettingsViewModel.settings` StateFlow + `setScrcpyLaunch` (Task 2).
- Produces: persisted profile on Start; opt states seeded from the profile on load.

- [ ] **Step 1: Add `settingsVm` param to `DeviceOverviewScreen`**

In `DeviceOverviewScreen.kt`, add a nullable param (after `scrcpyLauncher`) and the import:

```kotlin
import com.adbgui.core.domain.ScrcpyLaunchProfile
import androidx.compose.runtime.collectAsState
```

```kotlin
@Composable
fun DeviceOverviewScreen(
    deviceInfoVm: DeviceInfoViewModel,
    remoteVm: RemoteViewModel,
    onOpenScreenshot: () -> Unit,
    screenshotLoading: Boolean,
    selectedSerial: String?,
    scrcpyInstaller: ScrcpyInstaller,
    scrcpyLocator: WindowsScrcpyLocator,
    scrcpyLauncher: ScrcpyLauncher,
    settingsVm: SettingsViewModel? = null,   // NEW — null = no persistence (tests/headless)
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 2: Seed opt states from the loaded profile**

Just after the existing `val optRecordPath = remember { mutableStateOf(ScrcpyOptions().recordPath.orEmpty()) }` (and the other `opt*` declarations), add a reactive loader that re-applies the profile whenever `scrcpyLaunch` changes (initial async load + after each save — on save the values are what the user just launched, so re-apply is a no-op):

```kotlin
    val settingsState = settingsVm?.settings?.collectAsState()?.value
    LaunchedEffect(settingsState?.scrcpyLaunch) {
        val p = settingsState?.scrcpyLaunch ?: return@LaunchedEffect
        optMaxSize.value = p.maxSize.toString()
        optMaxFps.value = p.maxFps.toString()
        optStayAwake.value = p.stayAwake
        optTurnScreenOff.value = p.turnScreenOff
        optAlwaysOnTop.value = p.alwaysOnTop
        optFullscreen.value = p.fullscreen
        optNoAudio.value = p.noAudio
        optRecord.value = !p.recordFolder.isNullOrBlank()
        optRecordPath.value = p.recordFolder ?: ""
    }
```

(`LaunchedEffect` import already present; `collectAsState` added in Step 1.)

- [ ] **Step 3: Save the profile on Start**

In the Start button's `onClick`, after the `ScrcpyOptions(...)` is built (right before `scrcpyRunning.value = true`), build and persist the profile. Find the existing block:

```kotlin
                                val options = ScrcpyOptions(
                                    maxSize = optMaxSize.value.toIntOrNull() ?: 0,
                                    stayAwake = optStayAwake.value,
                                    turnScreenOff = optTurnScreenOff.value,
                                    recordPath = recordPath,
                                    alwaysOnTop = optAlwaysOnTop.value,
                                    fullscreen = optFullscreen.value,
                                    maxFps = optMaxFps.value.toIntOrNull() ?: 0,
                                    noAudio = optNoAudio.value,
                                )
```

Add immediately after it:

```kotlin
                                settingsVm?.setScrcpyLaunch(
                                    ScrcpyLaunchProfile(
                                        maxSize = optMaxSize.value.toIntOrNull() ?: 0,
                                        stayAwake = optStayAwake.value,
                                        turnScreenOff = optTurnScreenOff.value,
                                        alwaysOnTop = optAlwaysOnTop.value,
                                        fullscreen = optFullscreen.value,
                                        maxFps = optMaxFps.value.toIntOrNull() ?: 0,
                                        noAudio = optNoAudio.value,
                                        recordFolder = if (optRecord.value) optRecordPath.value.ifBlank { null } else null,
                                    )
                                )
```

- [ ] **Step 4: Thread `settingsVm` through `AppShell`**

In `AppShell.kt`, the `DeviceOverviewScreen(...)` call (inside the `NavPage.DEVICE_OVERVIEW` branch) — add `settingsVm = settingsVm,`:

```kotlin
                        DeviceOverviewScreen(
                            deviceInfoVm = deviceOverviewDeviceInfoVm,
                            remoteVm = deviceOverviewRemoteVm,
                            onOpenScreenshot = onOpenScreenshot,
                            screenshotLoading = screenshotLoading,
                            selectedSerial = selected,
                            scrcpyInstaller = scrcpyInstaller,
                            scrcpyLocator = scrcpyLocator,
                            scrcpyLauncher = scrcpyLauncher,
                            settingsVm = settingsVm,
                        )
```

- [ ] **Step 5: Compile**

Run: `./gradlew :desktop:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run full test suite (no regressions)**

Run: `./gradlew :core:test :desktop:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (the new `settingsVm` param has a default of `null`, so existing `DeviceOverviewScreen` usages/tests still compile; `SettingsViewModel` is already constructed in `Main.kt` and passed to `AppShell`).

- [ ] **Step 7: Smoke test** — `./gradlew :desktop:run`
  - Set some scrcpy options (e.g. check 置顶 + 关音频, resolution cap 1280, record toggle + a folder).
  - Click 开始投屏 (scrcpy launches with those flags — verify as before).
  - Close the app, relaunch.
  - Confirm the same options are pre-filled in the panel (loaded from `settings.json`).
  - Optional: inspect `%APPDATA%/AdbGui/settings.json` — a `scrcpyLaunch` object is present.

- [ ] **Step 8: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceOverviewScreen.kt \
        desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt
git commit -m "feat(desktop): persist + reload scrcpy launch options across restarts"
```

---

## Self-Review

**1. Spec coverage:** Persist 8 launch fields → Task 1 (model + Settings) + Task 3 (UI load/save). `SettingsViewModel` wiring → Task 2. Record folder persists (null = off). Defaults mirror `ScrcpyOptions`. ✅

**2. Placeholder scan:** No TBD/TODO. Each code step has real code. The `settingsVm?` nullable default makes the param optional for existing call sites/tests. ✅

**3. Type consistency:** `ScrcpyLaunchProfile` field names (`maxSize/stayAwake/turnScreenOff/alwaysOnTop/fullscreen/maxFps/noAudio/recordFolder`) used identically in Task 1 (def), Task 2 (test), Task 3 (load + save). `SettingsViewModel.setScrcpyLaunch(profile)` signature matches Task 2 def and Task 3 call. `Settings.scrcpyLaunch` field name matches Task 1 def and Task 3 read (`settingsState.scrcpyLaunch`). ✅

**4. TDD:** Task 1 (SettingsStore roundtrip) + Task 2 (SettingsViewModel) both failing-test → impl → green. Task 3 is pure UI wiring (no new logic to unit-test beyond the model); covered by compile + existing suite + manual smoke. ✅

**5. Red lines:** `:core` gains only a `@Serializable` data class + one `Settings` field (no UI/process). UI reads `SettingsViewModel.settings` StateFlow (existing approved pattern) + calls `setScrcpyLaunch`. No direct `SettingsStore` access from UI. Atomic write + injected `io` reused. ✅

No gaps. Plan complete.

---

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-08-20-scrcpy-options-persist.md`. Open a fresh Claude window and run:

> 读 `docs/superpowers/plans/2026-08-20-scrcpy-options-persist.md`,按 CLAUDE.md 约定执行(TDD + 每任务一个提交)。

Two execution options: (1) Subagent-Driven (fresh subagent per task, review between) — recommended; (2) Inline in one session.
