# ADB GUI 在线更新 — 实现计划（阶段 3：启动检查 + 上次检查展示 + 主窗口 banner）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** 启动时后台静默检查更新（可设置开关）；设置页展示"上次检查"时间/错误 + `checkOnStartup` 开关；主窗口顶部一个可折叠的更新提示条（"发现新版本"），折叠偏好按版本持久化。

**Architecture:** `:core` 加回 `checkOnStartup` + `dismissedVersion` 字段（阶段 1 删了 `checkOnStartup`，阶段 3 重新引入并真正消费）。`KtorUpdateManifestFetcher` 加 HTTP 状态校验（404→"HTTP 404"而非"invalid manifest"，阶段 2 给 downloader 加了、fetcher 漏了）。启动检查在 `Main.kt` 的 `LaunchedEffect` 里按 `checkOnStartup` 触发。主窗口 banner 放 `AppShell` 的 `TopBar/Divider` 下方，`Available` 时显示，`dismissedVersion == manifest.version` 时不显示。

**Tech Stack:** Kotlin 2.1.20 / KMP，kotlinx-coroutines 1.9.0，Ktor 3.0.3（`:desktop`），Compose Multiplatform 1.7.3。

**Spec:** `docs/superpowers/specs/2026-09-03-auto-update-design.md` §11 阶段 3 + §6 状态机 + §8 安全。

**Stage 1+2 已合并进 master（commit 9f6be06）：** 完整检查 + 下载 + 安装流程已落地。

## Global Constraints

- 红线：`:core` 不依赖 UI / Compose / `java.awt` / Ktor；`:desktop` 才用 Ktor。
- `:core` I/O 注入 Dispatcher。
- 不留死代码：`checkOnStartup` + `dismissedVersion` 必须有消费者（本阶段真正消费），不许再加占位字段。
- 跨线程 var `@Volatile`/`Mutex`/`StateFlow`。
- 错误本地化，不弹模态。
- Conventional Commits。包根 `com.adbgui.core.update.*` / `com.adbgui.desktop.*`。
- 用户偏好：回复中文，代码/commit 英文。
- 测试：`./gradlew :core:test` / `:desktop:test`。
- MEMORY: VM 用 `MutableStateFlow`+`asStateFlow()`，no `stateIn`。
- MEMORY: Composable 内 VM/object 创建包 `remember{}`。

## 文件结构

`:core`（改）：
- `settings/SettingsStore.kt` — `UpdateSettings` 加 `checkOnStartup: Boolean = true` + `dismissedVersion: String? = null`。
- `settings/SettingsStoreTest.kt` — 默认值 + round-trip 测试。

`:desktop/platform`（改）：
- `KtorUpdateManifestFetcher.kt` — HTTP 状态校验：`resp.status.isSuccess()` 否则抛 `IOException("HTTP ${status}")`。

`:desktop`（改）：
- `ui/SettingsViewModel.kt` — 加 `setCheckOnStartup(b)` + `dismissUpdateVersion(v)`。
- `ui/SettingsScreen.kt` — 更新区加：上次检查时间/错误展示 + `checkOnStartup` 开关。
- `ui/AppShell.kt` — `TopBar/Divider` 下方加 `UpdateBanner`（`Available` 时显示，可折叠）。
- `ui/update/UpdateBanner.kt`（新）— 独立 Composable。
- `ui/update/UpdateViewModel.kt` — `dismissCurrentUpdate()`：写 `dismissedVersion = manifest.version`，banner 隐藏。
- `ui/update/UpdateViewModelTest.kt` — `dismissCurrentUpdate` 测试。
- `ui/i18n/Strings.kt` — 新键。
- `main/Main.kt` — `LaunchedEffect` 里按 `checkOnStartup` 触发启动检查。
- `main/CompositionRoot.kt` — 无新构造（VM 已在）；可能把启动检查放 `start()`。

---

### Task 1: `:core` — `UpdateSettings` 加 `checkOnStartup` + `dismissedVersion`

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt`
- Modify: `core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `UpdateSettings` 增 `val checkOnStartup: Boolean = true` + `val dismissedVersion: String? = null`。

> 两个字段在本阶段都有消费者（Task 4/5 真正读写），不是占位。

- [ ] **Step 1: Write failing test**（在 `SettingsStoreTest` 的 `update_settings_default_and_round_trip` 里追加断言：默认 `checkOnStartup == true`、`dismissedVersion == null`；round-trip 改 `dismissedVersion = "1.1.0"` 后能读回）。
- [ ] **Step 2: Run → FAIL**（字段未解析）。
- [ ] **Step 3: Implement** — 在 `UpdateSettings` 加两字段。
- [ ] **Step 4: Run → PASS**。
- [ ] **Step 5: Commit** `feat(core): re-add checkOnStartup + add dismissedVersion to UpdateSettings`。

---

### Task 2: `:desktop/platform` — `KtorUpdateManifestFetcher` HTTP 状态校验

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/platform/KtorUpdateManifestFetcher.kt`

**Interfaces:**
- Produces: `fetch(url)` 在 `resp.status.isSuccess()` 为 false 时抛 `IOException("HTTP ${status.value}")`，由 `UpdateChecker` 的 `catch(Throwable)` 捕获 → `Error("fetch failed: HTTP 404")`。

- [ ] **Step 1: Implement** — `override suspend fun fetch(url): String = withContext(io) { val resp = client.get(url); if (!resp.status.isSuccess()) throw java.io.IOException("HTTP ${resp.status.value}"); resp.bodyAsText() }`。`import io.ktor.http.isSuccess`（参考 `KtorUpdateDownloader.kt`）。
- [ ] **Step 2: Build** `./gradlew :desktop:compileKotlin -q`。
- [ ] **Step 3: Commit** `fix(desktop): validate HTTP status in KtorUpdateManifestFetcher`。

---

### Task 3: `:desktop` — `UpdateViewModel.dismissCurrentUpdate()` + `UpdateViewModel` 启动检查入口

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/update/UpdateViewModel.kt`
- Modify: `desktop/src/test/kotlin/com/adbgui/desktop/ui/update/UpdateViewModelTest.kt`

**Interfaces:**
- Produces: `fun dismissCurrentUpdate()` — 读当前 `Available(m).manifest.version`，写 `settings.update.dismissedVersion = version`。banner 据此隐藏。

- [ ] **Step 1: Write failing test** — `dismiss_current_update_persists_version`：构造 VM，`checkForUpdates`→Available，`dismissCurrentUpdate()`，断言 `store.load().update.dismissedVersion == "1.1.0"`。
- [ ] **Step 2: Run → FAIL**。
- [ ] **Step 3: Implement** — `fun dismissCurrentUpdate() = scope.launch { val s = _state.value as? UpdateState.Available ?: return@launch; store.update { it.copy(update = it.update.copy(dismissedVersion = s.manifest.version)) } }`。
- [ ] **Step 4: Run → PASS**。
- [ ] **Step 5: Commit** `feat(desktop): add UpdateViewModel.dismissCurrentUpdate`。

---

### Task 4: `:desktop` — `SettingsViewModel` 加 `setCheckOnStartup` + 设置页上次检查展示 + 开关

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsViewModel.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`
- Modify: `desktop/src/test/kotlin/com/adbgui/desktop/ui/SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `SettingsViewModel.setCheckOnStartup(b: Boolean)`。

- [ ] **Step 1: Write failing test** — `setCheckOnStartup_persists`（仿 `setTheme_persists`）。
- [ ] **Step 2: Run → FAIL**。
- [ ] **Step 3: Implement** — `SettingsViewModel.setCheckOnStartup(b) = scope.launch { store.update { it.copy(update = it.update.copy(checkOnStartup = b)) }; refresh() }`。
- [ ] **Step 4: SettingsScreen 更新区加** — "启动时检查更新" `Checkbox`（绑 `settings.update.checkOnStartup`，调 `settingsVm.setCheckOnStartup`）；"上次检查：{lastCheckAt ?: '从未'}" + 若 `lastCheckError != null` 折叠展示。i18n 键：`update_check_on_startup`、`update_last_check`、`update_last_check_never`、`update_last_error`。
- [ ] **Step 5: Run `:desktop:test` → PASS**。
- [ ] **Step 6: Commit** `feat(desktop): show last-check + checkOnStartup toggle in settings`。

---

### Task 5: `:desktop` — 主窗口 `UpdateBanner` + AppShell 接入

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/update/UpdateBanner.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`

**Interfaces:**
- Produces: `@Composable fun UpdateBanner(updateVm, settingsVm)` — `updateVm.state` 为 `Available(m)` 且 `settings.update.dismissedVersion != m.version` 时显示一条横幅：版本号 + notes + "下载并安装"（调 `updateVm.downloadUpdate()`）+ "打开下载页"（`updateVm.openDownloadPage()`）+ "✕"（`updateVm.dismissCurrentUpdate()`）。其他状态（Downloading/Ready/Installing）不显示（这些只在设置页处理，避免主窗口太喧哗）。

> 折叠偏好：`dismissedVersion` 命中当前 `manifest.version` → 不显示。新版本 `version` 不同 → 重新显示。无需额外布尔。

- [ ] **Step 1: i18n 键** — `update_banner_title`（"发现新版本 %s"）、`update_dismiss`（"以后再说"）。
- [ ] **Step 2: Write `UpdateBanner.kt`** — Composable，读 `updateVm.state.collectAsState()` + `settingsVm.settings.collectAsState()`，`when` 判断 Available + dismissedVersion，显示横幅（M2 `Surface`/`Button`/`TextButton`/`IconButton`）。
- [ ] **Step 3: AppShell 接入** — 在 `Column { TopBar(); Divider(); /* 这里插 UpdateBanner */ Surface { when{...} } }`，`if (updateVm != null && settingsVm != null) UpdateBanner(updateVm!!, settingsVm!!)`。
- [ ] **Step 4: Build + run** `./gradlew :desktop:compileKotlin -q` → BUILD SUCCESSFUL。
- [ ] **Step 5: Commit** `feat(desktop): add main-window UpdateBanner with per-version dismiss`。

---

### Task 6: `:desktop` — 启动后台静默检查

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt`

**Interfaces:**
- Consumes: `settings.update.checkOnStartup`（Task 1）+ `root.updateViewModel.checkForUpdates()`。

- [ ] **Step 1: Implement** — 在 `Main.kt` 的 `LaunchedEffect(Unit) { root.start() }` 附近加：`LaunchedEffect(Unit) { if (settings.update.checkOnStartup) root.updateViewModel.checkForUpdates() }`（`settings` 已在 `runBlocking` 里同步加载，可直接读）。
> 启动检查是后台静默：VM 设 `Checking`→结果，但设置页未默认打开，用户看不到 `Checking`；banner 仅在 `Available` + 未折叠时出现。
- [ ] **Step 2: Build + run** `./gradlew :desktop:compileKotlin -q`；手动 `:desktop:run` 验证启动不报错、banner（如有新版本）出现。
- [ ] **Step 3: Commit** `feat(desktop): startup background update check gated by checkOnStartup`。

---

## 自检结果

**Spec 覆盖**：§11 阶段 3 三项（启动静默检查 → Task 6；上次检查时间/错误展示 → Task 4；更新条折叠偏好持久化 → Task 5 的 `dismissedVersion`）+ `checkOnStartup` 重新引入并消费 → Task 1/4/6。顺手补 fetcher HTTP 状态校验（Task 2，阶段 2 遗漏）。

**Placeholder 扫描**：无 TBD/TODO。`UpdateSourceRegistry` 占位 URL 仍待发布前填（阶段 1 ledger 已记，不在本计划范围）。

**类型一致性**：`UpdateSettings.checkOnStartup`/`dismissedVersion` / `UpdateViewModel.dismissCurrentUpdate()` / `SettingsViewModel.setCheckOnStartup(b)` / `UpdateBanner(updateVm, settingsVm)` —— 跨任务签名一致。

**Rulings**（plan-level，相对 spec §11 的细化）：
- 折叠偏好用 `dismissedVersion: String?`（按版本，而非布尔"已折叠"）——新版本自动重新提示，符合"用户以后再说"语义而非永久静音。
- banner 仅在 `Available` 显示；`Downloading`/`Ready`/`Installing` 只在设置页处理，避免主窗口在安装流程中喧哗。
- 启动检查放 `Main.kt` 的 `LaunchedEffect`（`settings` 已同步加载），不放 `CompositionRoot.start()`（那里 settings 异步加载，读不到 `checkOnStartup`）。

## 执行交接

计划存 `docs/superpowers/plans/2026-09-08-auto-update-stage3.md`。Subagent-Driven 执行，新 worktree 从本地 master HEAD 开分支（baseRef=head，因 master 未 push）。
