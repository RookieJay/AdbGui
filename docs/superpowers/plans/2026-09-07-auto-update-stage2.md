# ADB GUI 在线更新 — 实现计划（阶段 2：下载与安装）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在阶段 1（检查 + 展示）之上实现"下载 MSI → sha256 校验 → 触发 `msiexec` 升级 → 退出本进程"，并为便携版提供"打开下载页"。

**Architecture:** 下载逻辑藏 `UpdateDownloader` 接口（`:core` 定义、`:desktop/platform` Ktor 实现）后，`UpdateViewModel` 扩展状态机：`Available → Downloading(progress) → Ready(path) → Installing → exit`。`MsiUpgrader`（`:desktop/platform`）spawn `msiexec /i`，VM 随后 `exitProcess(0)`。便携版用 `PortableUpdateNotifier`（`cmd /c start` 打开浏览器）。接口隔离 + 注入退出函数保证 `:core`/VM 可单测。

**Tech Stack:** Kotlin 2.1.20 / KMP，kotlinx-coroutines 1.9.0，Ktor 3.0.3（`:desktop`），Compose Multiplatform 1.7.3，Turbine 1.2.0。

**Spec:** `docs/superpowers/specs/2026-09-03-auto-update-design.md`（§5 架构落点 `UpdateDownloader`/`MsiUpgrader`/`PortableUpdateNotifier`；§6 状态机 `Available→Downloading→Ready→Installing`；§7 数据流 4-6；§8 安全 sha256/原子写/可取消）。

**Stage 1 已落地（commits 52847bf..e442e9f）：** `UpdateVersion`/`UpdateManifest`/`UpdateManifestParser`/`UpdateVersionComparer`/`UpdateSource`/`UpdateSourceRegistry`/`UpdateManifestFetcher`/`UpdateChecker`/`UpdateCheckResult`/`UpdateSettings`（无 `checkOnStartup`）/`AppMeta`/`KtorUpdateManifestFetcher`/`UpdateViewModel`（状态：`Idle/Checking/NoUpdate/Available(version,notes,url)/Error`）/设置页检查入口。

## Global Constraints

- 红线：`:core` 不依赖 UI / Compose / `java.awt` / `javax.swing`；**`:core` 也不引入 Ktor**（下载走 `UpdateDownloader` 接口，Ktor 实现在 `:desktop/platform`）。
- `:core` I/O 注入 `CoroutineDispatcher`（构造参数 `io = Dispatchers.IO`，测试传 `Unconfined`）。
- 不留死代码：无占位、未引用 class/val/param。**`UpdateSettings.checkOnStartup` 已在阶段 1 删除**，阶段 3 重新引入时再加。
- 跨线程 `var` 必须 `@Volatile` / `Mutex` / `MutableStateFlow`。
- 错误本地化，不弹模态；解析/校验失败保留原文兜底。
- 原子写：`<version>.msi.part` → `ATOMIC_MOVE` 重命名；sha256 校验失败即删文件。
- 仅 HTTPS；版本不降级（阶段 1 已实现）。
- Conventional Commits。包根 `com.adbgui.core.update.*` / `com.adbgui.desktop.platform.*` / `com.adbgui.desktop.ui.update.*`。
- 用户偏好：回复中文，代码/commit 英文。
- 测试：`./gradlew :core:test` / `:desktop:test`；单测 `--tests "com.adbgui.core.update.XxxTest"`。
- MEMORY: VM 用 `MutableStateFlow`+`asStateFlow()`，**不许** `stateIn(scope, WhileSubscribed)`（runTest 坑）。
- MEMORY: Composable 内 VM/object 创建包 `remember{}`（但 `UpdateViewModel` 在 `CompositionRoot` 构造，不适用）。
- 既有模式：`ProcessBuilder` 启动外部进程（见 `JvmAdbProcessRunner`/`ScrcpyLauncher`/`ShellLauncher`）；`WindowsConfigDirProvider.configDir()` = `%APPDATA%/AdbGui/`。

## 文件结构

`:core`（新增/改）：
- `update/UpdateDownloadResult.kt` — sealed: `Success(msiPath)` / `HashMismatch(expected, actual)` / `NetworkError(message)` / `Cancelled`。
- `update/UpdateDownloader.kt` — interface `suspend fun download(url, sha256, onProgress: (Float)->Unit): UpdateDownloadResult`。

`:desktop/platform`（新增）：
- `KtorUpdateDownloader.kt` — Ktor 流式下载到 `%APPDATA%/AdbGui/updates/<sha256>.msi.part` → `ATOMIC_MOVE` 重命名 → sha256 校验。
- `MsiUpgrader.kt` — `fun launch(msiPath)`：`ProcessBuilder("msiexec","/i",path).inheritIO().start()`（detached）。
- `PortableUpdateNotifier.kt` — `fun openDownloadPage(url)`：`ProcessBuilder("cmd","/c","start","",url)`（Windows）。

`:desktop`（改）：
- `ui/update/UpdateViewModel.kt` — 扩展 `UpdateState`（加 `Downloading`/`Ready`/`Installing`；`Available` 改带 `manifest`）；加 `downloadUpdate()`/`cancelDownload()`/`installNow()`/`openDownloadPage()`。
- `ui/update/UpdateViewModelTest.kt` — 新状态机分支单测。
- `ui/SettingsScreen.kt` — 更新区：下载并安装按钮、进度条、取消、打开下载页。
- `ui/i18n/Strings.kt` — 新键。
- `main/CompositionRoot.kt` — 构造 downloader/msiUpgrader/notifier 注入 VM；注入 `exit: (Int)->Nothing = ::exitProcess`。

---

### Task 1: `UpdateDownloadResult` + `UpdateDownloader` interface（`:core`）

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateDownloadResult.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateDownloader.kt`

**Interfaces:**
- Produces: `sealed class UpdateDownloadResult { data class Success(val msiPath: String); data class HashMismatch(val expected: String, val actual: String); data class NetworkError(val message: String, val cause: Throwable? = null); object Cancelled }`；`interface UpdateDownloader { suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit): UpdateDownloadResult }`.

- [ ] **Step 1: Write implementation**（接口 + sealed，无逻辑测试——纯类型定义；行为由 VM 单测覆盖）。

`UpdateDownloadResult.kt`：
```kotlin
package com.adbgui.core.update

sealed class UpdateDownloadResult {
    data class Success(val msiPath: String) : UpdateDownloadResult()
    data class HashMismatch(val expected: String, val actual: String) : UpdateDownloadResult()
    data class NetworkError(val message: String, val cause: Throwable? = null) : UpdateDownloadResult()
    object Cancelled : UpdateDownloadResult()
}
```

`UpdateDownloader.kt`：
```kotlin
package com.adbgui.core.update

interface UpdateDownloader {
    suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit): UpdateDownloadResult
}
```

- [ ] **Step 2: Build sanity**
Run: `./gradlew :core:compileKotlin -q` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit**
```bash
git add core/src/main/kotlin/com/adbgui/core/update/UpdateDownloadResult.kt core/src/main/kotlin/com/adbgui/core/update/UpdateDownloader.kt
git commit -m "feat(core): add UpdateDownloader interface + download result"
```

---

### Task 2: `KtorUpdateDownloader` 实现（`:desktop/platform`）

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/platform/KtorUpdateDownloader.kt`

**Interfaces:**
- Consumes: `UpdateDownloader`（Task 1）、`UpdateManifest.sha256`、Ktor `HttpClient`（`:desktop` 已有 ktor-client-cio）.
- Produces: `class KtorUpdateDownloader(configDir: Path, io: CoroutineDispatcher = Dispatchers.IO, logger: Logger) : UpdateDownloader`.

> 不写单测——HTTP + 文件系统行为靠阶段 2 集成验证（VM 单测用 fake downloader）。匹配阶段 1 `KtorUpdateManifestFetcher` 策略。

- [ ] **Step 1: Write implementation**

```kotlin
package com.adbgui.desktop.platform

import com.adbgui.core.log.Logger
import com.adbgui.core.update.UpdateDownloadResult
import com.adbgui.core.update.UpdateDownloader
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class KtorUpdateDownloader(
    private val configDir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logger: Logger,
) : UpdateDownloader {
    private val client = HttpClient()

    override suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit): UpdateDownloadResult = withContext(io) {
        val updatesDir = configDir.resolve("updates").also { Files.createDirectories(it) }
        val partFile = updatesDir.resolve("$sha256.msi.part")
        val finalFile = updatesDir.resolve("$sha256.msi")
        try {
            val resp = client.get(url)
            val channel = resp.bodyAsChannel()
            val total = resp.contentLength()?.takeIf { it > 0 } ?: -1L
            var read = 0L
            val md = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(partFile).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = channel.readAvailable(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    read += n
                    if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) {
                Files.deleteIfExists(partFile)
                logger.warn("update: sha256 mismatch expected=$sha256 actual=$actual")
                return@withContext UpdateDownloadResult.HashMismatch(sha256, actual)
            }
            Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            logger.info("update: downloaded ${finalFile.fileName} ($read bytes)")
            UpdateDownloadResult.Success(finalFile.toString())
        } catch (t: kotlinx.coroutines.CancellationException) {
            Files.deleteIfExists(partFile)
            throw t
        } catch (t: Throwable) {
            Files.deleteIfExists(partFile)
            logger.warn("update: download failed", t)
            UpdateDownloadResult.NetworkError(t.message ?: "unknown", t)
        }
    }
}
```

> 注：`CancellationException` 单独 catch 并删 `.part`（协程取消时清理）。`bodyAsChannel` + `readAvailable` 来自 `ktor-utils`（ktor-client-cio 传递依赖）。`contentLength()` 是 `HttpResponse.contentLength()`。

- [ ] **Step 2: Build sanity**
Run: `./gradlew :desktop:compileKotlin -q` → BUILD SUCCESSFUL. 修任何未解析的 import（`contentLength` 可能需要 `import io.ktor.client.statement.contentLength` 或 `HttpResponse.contentLength()`——按 Ktor 3.0.3 API 调整）。
- [ ] **Step 3: Commit**
```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/platform/KtorUpdateDownloader.kt
git commit -m "feat(desktop): add KtorUpdateDownloader with sha256 verify + atomic rename"
```

---

### Task 3: `MsiUpgrader`（`:desktop/platform`）

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/platform/MsiUpgrader.kt`

**Interfaces:**
- Produces: `class MsiUpgrader { fun launch(msiPath: String) }` — spawn `msiexec /i <path>`（perUserInstall 免提权），detached。caller 负责 `exitProcess(0)` 让文件锁释放。

- [ ] **Step 1: Write implementation**

```kotlin
package com.adbgui.desktop.platform

import java.io.File

/**
 * 启动 msiexec /i 升级 MSI（perUserInstall 免管理员）。
 * 调用方在 launch 后必须 exitProcess(0) 释放已安装文件锁。
 */
class MsiUpgrader {
    fun launch(msiPath: String) {
        val pb = ProcessBuilder("msiexec", "/i", File(msiPath).absolutePath)
            .redirectErrorStream(true)
        pb.directory(null)  // inherit cwd
        pb.start()
    }
}
```

> `redirectErrorStream(true)` + 不 `inheritIO`（不阻塞在 msiexec 输出上）。`pb.directory(null)` 继承当前工作目录。

- [ ] **Step 2: Build sanity**
Run: `./gradlew :desktop:compileKotlin -q` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit**
```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/platform/MsiUpgrader.kt
git commit -m "feat(desktop): add MsiUpgrader to launch msiexec"
```

---

### Task 4: `PortableUpdateNotifier`（`:desktop/platform`）

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/platform/PortableUpdateNotifier.kt`

**Interfaces:**
- Produces: `class PortableUpdateNotifier { fun openDownloadPage(url: String) }` — Windows 用 `cmd /c start` 打开默认浏览器。

- [ ] **Step 1: Write implementation**

```kotlin
package com.adbgui.desktop.platform

/** 便携版更新：用默认浏览器打开下载页（不自替换运行中目录）。 */
class PortableUpdateNotifier {
    fun openDownloadPage(url: String) {
        ProcessBuilder("cmd", "/c", "start", "", url).redirectErrorStream(true).start()
    }
}
```

> `start "" url` 第一个空串是 start 的窗口标题占位（避免含 & 的 URL被当命令）。Windows-only，阶段 2 范围内。

- [ ] **Step 2: Build sanity**
Run: `./gradlew :desktop:compileKotlin -q` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit**
```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/platform/PortableUpdateNotifier.kt
git commit -m "feat(desktop): add PortableUpdateNotifier to open download page"
```

---

### Task 5: 扩展 `UpdateViewModel` + `UpdateState`（`:desktop`）

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/update/UpdateViewModel.kt`
- Modify: `desktop/src/test/kotlin/com/adbgui/desktop/ui/update/UpdateViewModelTest.kt`

**Interfaces:**
- Consumes: `UpdateDownloader`（Task 1）、`MsiUpgrader`（Task 3）、`PortableUpdateNotifier`（Task 4）、阶段 1 的 `UpdateChecker`/`UpdateSourceRegistry`/`SettingsStore`/`UpdateManifest`/`UpdateCheckResult`/`UpdateState`.
- Produces: 扩展后的 `UpdateState`（见下），`UpdateViewModel(downloader, msiUpgrader, notifier, exit, checker, store, scope)` 新增构造参数；新方法 `downloadUpdate()`/`cancelDownload()`/`installNow()`/`openDownloadPage()`.

**`UpdateState` 改动**（破坏性改 `Available`）：
```kotlin
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object NoUpdate : UpdateState()
    data class Available(val manifest: UpdateManifest) : UpdateState()   // 改：带 manifest（含 url+sha256）
    data class Downloading(val progress: Float) : UpdateState()
    data class Ready(val msiPath: String, val manifest: UpdateManifest) : UpdateState()
    object Installing : UpdateState()
    data class Error(val message: String) : UpdateState()
}
```
> 阶段 1 的 `Available(version, notes, url)` 改为 `Available(manifest)`；阶段 1 测试里 `(s as Available).version` 改为 `(s as Available).manifest.version`，`SettingsScreen` 的 `s.version` 改为 `s.manifest.version`、`s.notes`→`s.manifest.notes`、`s.url`（下载页）→`s.manifest.url`。

**`UpdateViewModel` 改动**：
- 构造加：`private val downloader: UpdateDownloader, private val msiUpgrader: MsiUpgrader, private val notifier: PortableUpdateNotifier, private val exit: (Int) -> Nothing = ::exitProcess`。
- `@Volatile private var downloadJob: Job? = null`（取消用，跨线程访问）。
- `checkForUpdates()` 的 `UpdateAvailable` 分支：`_state.value = UpdateState.Available(m)`（用 manifest）。
- `fun downloadUpdate() = scope.launch { ... }`：读当前 `state`，若 `Available(m)` → 设 `Downloading(0f)` → `downloader.download(m.url, m.sha256) { p -> _state.value = Downloading(p) }` → `when(result)`：`Success(path)`→`Ready(path, m)`；`HashMismatch`→`Error("sha256 校验失败")`；`NetworkError`→`Error(...)`；`Cancelled`→`Available(m)`（回到可下载）。
- `fun cancelDownload()`：`downloadJob?.cancel()`；设 `Available` 状态（若当前是 Downloading）。
- `fun installNow()`：若 `Ready(path, m)` → 设 `Installing` → `msiUpgrader.launch(path)` → `exit(0)`。
- `fun openDownloadPage()`：若 `Available(m)` 或 `Ready` → `notifier.openDownloadPage(m.url)`。

- [ ] **Step 1: Write/update the failing tests**

更新 `UpdateViewModelTest.kt`：阶段 1 的 `check_finds_update` 改 `(s as Available).manifest.version == "1.1.0"`；新增：

```kotlin
private class FakeDownloader(private val result: UpdateDownloadResult, val onProgress: (Float) -> Unit = {}) : UpdateDownloader {
    override suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit) = run { this@FakeDownloader.onProgress; result }
}

private class FakeMsiUpgrader {
    var launched: String? = null
    fun launch(path: String) { launched = path }
}

private class FakeNotifier {
    var opened: String? = null
    fun openDownloadPage(url: String) { opened = url }
}

@Test fun download_succeeds_to_ready() = runTest {
    val (vm, _, _) = vmWithState(this, manifest = "1.1.0", downloader = FakeDownloader(UpdateDownloadResult.Success("/tmp/x.msi")))
    vm.downloadUpdate()
    advanceUntilIdle()
    val s = vm.state.value
    assert(s is UpdateState.Ready)
    assertEquals("/tmp/x.msi", (s as UpdateState.Ready).msiPath)
}

@Test fun download_hash_mismatch_sets_error() = runTest {
    val (vm, _, _) = vmWithState(this, manifest = "1.1.0", downloader = FakeDownloader(UpdateDownloadResult.HashMismatch("a","b")))
    vm.downloadUpdate()
    advanceUntilIdle()
    assert(vm.state.value is UpdateState.Error)
}

@Test fun install_now_launches_msi_and_exits() = runTest {
    var exited = -1
    val msi = FakeMsiUpgrader()
    val (vm, _, _) = vmWithState(this, manifest = "1.1.0",
        downloader = FakeDownloader(UpdateDownloadResult.Success("/tmp/x.msi")),
        msiUpgrader = msi, exit = { exited = it; throw RuntimeException("exit") })
    vm.downloadUpdate(); advanceUntilIdle()
    try { vm.installNow(); advanceUntilIdle() } catch (e: RuntimeException) {}
    assertEquals("/tmp/x.msi", msi.launched)
    assertEquals(0, exited)
}

@Test fun open_download_page_for_portable() = runTest {
    val notifier = FakeNotifier()
    val (vm, _, _) = vmWithState(this, manifest = "1.1.0", notifier = notifier)
    vm.openDownloadPage()
    advanceUntilIdle()
    assertEquals("https://example.com/AdbGui-1.1.0.msi", notifier.opened)
}
```
（`vmWithState` 工厂：构造 store + checker（FakeFetcher 返回 1.1.0 manifest）+ downloader/msiUpgrader/notifier/exit，调 `vm.checkForUpdates()` 推到 Available，或直接设状态——但 `_state` 私有，最简单是先 `checkForUpdates` + `advanceUntilIdle` 让它走 Available。）

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.update.UpdateViewModelTest" -q`
Expected: FAIL — `Downloading`/`Ready`/`installNow`/`openDownloadPage` 等未解析，或 `Available(manifest)` 破坏阶段 1 测试。

- [ ] **Step 3: Write minimal implementation**
按上面 `UpdateState` + `UpdateViewModel` 改动写实现。import `kotlinx.coroutines.Job`、`kotlin.system.exitProcess`、`com.adbgui.core.update.UpdateDownloadResult`、`com.adbgui.desktop.platform.{MsiUpgrader,PortableUpdateNotifier}`。

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.update.UpdateViewModelTest" -q`
Expected: PASS（新 + 阶段 1 改后全过）。

- [ ] **Step 5: Commit**
```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/update/UpdateViewModel.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/update/UpdateViewModelTest.kt
git commit -m "feat(desktop): extend UpdateViewModel with download/install/open-page"
```

---

### Task 6: 设置页 UI 扩展（`:desktop`）

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`

**Interfaces:**
- Consumes: 扩展后的 `UpdateState`（Task 5）。
- Produces: 更新区 UI——下载并安装按钮、进度条（Downloading）、取消（Downloading）、打开下载页（Available/Ready）、安装（Ready→Installing）。

> 无新单测（UI 组装，VM 已测）。既有 `SettingsScreen` 用 M2。

- [ ] **Step 1: Add i18n keys**（zh + en）
zh:
```
"update_download_install" to "下载并安装",
"update_downloading" to "下载中…%d%%",
"update_cancel_download" to "取消下载",
"update_install_now" to "立即安装",
"update_installing" to "正在启动安装…",
"update_open_page" to "打开下载页",
"update_download_error" to "下载失败：%s",
"update_hash_error" to "校验失败：文件可能损坏",
```
en:
```
"update_download_install" to "Download and install",
"update_downloading" to "Downloading…%d%%",
"update_cancel_download" to "Cancel",
"update_install_now" to "Install now",
"update_installing" to "Starting installer…",
"update_open_page" to "Open download page",
"update_download_error" to "Download failed: %s",
"update_hash_error" to "Verification failed: file may be corrupt",
```

- [ ] **Step 2: Update the update section in SettingsScreen.kt**

把阶段 1 的 `when (val s = updateState)` 分支扩展为：
```kotlin
when (val s = updateState) {
    UpdateState.Checking -> Text(Strings.t("update_checking"))
    UpdateState.NoUpdate -> Text(Strings.t("update_no_update"))
    is UpdateState.Available -> {
        Text(Strings.t("update_available").format(s.manifest.version))
        if (s.manifest.notes != null) Text(s.manifest.notes)
        Button(onClick = { updateVm.downloadUpdate() }) { Text(Strings.t("update_download_install")) }
        TextButton(onClick = { updateVm.openDownloadPage() }) { Text(Strings.t("update_open_page")) }
    }
    is UpdateState.Downloading -> {
        val pct = (s.progress * 100).toInt()
        Text(Strings.t("update_downloading").format(pct))
        LinearProgressIndicator(progress = s.progress)
        Button(onClick = { updateVm.cancelDownload() }) { Text(Strings.t("update_cancel_download")) }
    }
    is UpdateState.Ready -> {
        Text(Strings.t("update_available").format(s.manifest.version))
        Button(onClick = { updateVm.installNow() }) { Text(Strings.t("update_install_now")) }
        TextButton(onClick = { updateVm.openDownloadPage() }) { Text(Strings.t("update_open_page")) }
    }
    UpdateState.Installing -> Text(Strings.t("update_installing"))
    is UpdateState.Error -> Text(Strings.t("update_error").format(s.message))
    UpdateState.Idle -> Unit
}
```
> imports: `androidx.compose.material3.LinearProgressIndicator`（若 M2 则用 `androidx.compose.material.LinearProgressIndicator`，按 SettingsScreen 现有 Material 版本）。`Available` 的 `s.version`/`s.notes`/`s.url` 全部改为 `s.manifest.version`/`s.manifest.notes`/`s.manifest.url`。

- [ ] **Step 3: Compile**
Run: `./gradlew :desktop:compileKotlin -q` → BUILD SUCCESSFUL. 修 import（M2 vs M3 LinearProgressIndicator）。
- [ ] **Step 4: Commit**
```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt
git commit -m "feat(desktop): extend settings update UI with download/install/progress"
```

---

### Task 7: CompositionRoot 装配（`:desktop`）

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/CompositionRoot.kt`

**Interfaces:**
- Consumes: `KtorUpdateDownloader`（Task 2）、`MsiUpgrader`（Task 3）、`PortableUpdateNotifier`（Task 4）、阶段 1 的 `UpdateChecker`/`SettingsStore`/`AppMeta`/scope/logger.

- [ ] **Step 1: Read CompositionRoot.kt**，在阶段 1 的 `updateFetcher`/`updateChecker`/`updateViewModel` 构造附近加：
```kotlin
val updateDownloader = KtorUpdateDownloader(configDir, io = kotlinx.coroutines.Dispatchers.IO, logger = fileLogger)
val msiUpgrader = MsiUpgrader()
val portableNotifier = PortableUpdateNotifier()
```
并把 `UpdateViewModel` 构造改为传入 `downloader = updateDownloader, msiUpgrader = msiUpgrader, notifier = portableNotifier`（`exit` 用默认 `::exitProcess`）。用既有 val 名（`configDir`/`fileLogger`/scope——读文件确认）。
> 若 `UpdateViewModel` 是 `data class` 或构造在 `Main.kt`/`AppShell`，按既有路径改（阶段 1 是在 CompositionRoot + AppShell/Main 串的）。

- [ ] **Step 2: Compile + run**
Run: `./gradlew :desktop:compileKotlin -q` → BUILD SUCCESSFUL.
Run: `./gradlew :desktop:test -q` → 全过（含 VM 新测试）。
- [ ] **Step 3: Commit**
```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/main/CompositionRoot.kt
git commit -m "feat(desktop): wire update downloader/upgrader/notifier into CompositionRoot"
```

---

## 自检结果

**Spec 覆盖**：§5 `UpdateDownloader`/`KtorUpdateDownloader`/`MsiUpgrader`/`PortableUpdateNotifier` → Task 1-4；§6 状态机 `Available→Downloading→Ready→Installing` → Task 5；§7 数据流 4-6（下载→校验→msiexec→退出）→ Task 2/3/5；§8 sha256/原子写/可取消/删残留 → Task 2。阶段 3（启动静默检查 + `lastCheckAt`/`Error` 展示 + `checkOnStartup` 重新引入）留待后续计划。

**Placeholder 扫描**：无 TBD/TODO；阶段 1 的 `OWNER/ADBGUI` 占位仍待发布前填（阶段 1 ledger 已记）。

**类型一致性**：`UpdateDownloadResult{Success(msiPath),HashMismatch(expected,actual),NetworkError(message,cause),Cancelled}` / `UpdateDownloader.download(url,sha256,onProgress)` / `MsiUpgrader.launch(msiPath)` / `PortableUpdateNotifier.openDownloadPage(url)` / `UpdateState{...,Available(manifest),Downloading(progress),Ready(msiPath,manifest),Installing,...}` —— 跨任务签名一致。`Available` 由阶段 1 的 `(version,notes,url)` 改为 `(manifest)` —— Task 5/6 同步更新测试与 UI。

**Rulings**（plan-level，相对 spec §5/§6 的细化）：
- `UpdateDownloader` 接口在 `:core`、Ktor 实现在 `:desktop/platform`（与阶段 1 `UpdateManifestFetcher` 同模式；`:core` 不引 Ktor）。
- `exitProcess` 作为 `(Int)->Nothing` 注入 VM，便于单测 `installNow` 的退出副作用（fake 抛异常截断）。
- `MsiUpgrader.launch` 只 spawn msiexec，退出由 VM 调 `exit(0)` —— 职责清晰，launch 可被 fake 验证。
- 不做运行时"MSI vs 便携"检测（`dirChooser=true` 允许自定义安装路径，检测不可靠）：`Available` 同时显示"下载并安装"+"打开下载页"，便携用户也可选"打开下载页"手动升级。spec §2 的"便携版仅打开下载页"在此放宽为"两者都显示"——更鲁棒，不依赖脆弱检测。

## 执行交接

计划已存 `docs/superpowers/plans/2026-09-07-auto-update-stage2.md`。Subagent-Driven 执行（承接阶段 1 同一 worktree/分支 `worktree-auto-update-stage1`，提交叠在 `e442e9f` 之上）。
