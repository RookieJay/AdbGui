# ADB GUI 在线更新 — 加固计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 补齐 auto-update 推迟的 8 项加固，使其可发布给真实用户。

**Architecture:** 见 spec §5。加固点：HTTP 超时/UA（防 CDN 卡死无限挂）、`.msi` 清理、无 Content-Length 时进度、并发检查去重、URL 引号、`UpdateVersion` 严格化 + 测试、源感知 MSI url 代理改写（镜像源真正代理下载）、`UpdateState.Error` typed（折叠原文）。

**Tech Stack:** Kotlin 2.1.20，Ktor 3.0.3，Compose Multiplatform 1.7.3。

**Spec:** `docs/superpowers/specs/2026-09-03-auto-update-design.md` §5/§6/§8（§8 安全：sha256/原子写/可取消；§8 隐含：超时、原文兜底）。

**Stage 1-3 已合并 master（commit ae9aeaa 及之前）。**

## Global Constraints

- `:core` 不依赖 UI/Compose/awt/Ktor；`:desktop` 才用 Ktor。
- `:core` I/O 注入 Dispatcher；不留死代码；跨线程 var `@Volatile`。
- 错误本地化，不弹模态；解析失败保留原文兜底（`UpdateState.Error` 携 raw）。
- Conventional Commits。包根 `com.adbgui.core.update.*` / `com.adbgui.desktop.*`。
- 用户偏好：中文回复，代码/commit 英文。
- 测试：`./gradlew :core:test` / `:desktop:test`。
- MEMORY: VM 用 `MutableStateFlow`+`asStateFlow()`，no `stateIn`。

## 文件结构（按任务）

### Task 1: HTTP 超时 + User-Agent（fetcher + downloader）
- Modify: `desktop/.../platform/KtorUpdateManifestFetcher.kt` + `KtorUpdateDownloader.kt`
- 给两个 `HttpClient` 配 `HttpTimeout`（connectTimeoutMillis=10s、requestTimeoutMillis=30s、socketTimeoutMillis=30s）+ `UserAgent`（`AdbGui/${AppMeta.APP_VERSION}`）。
- imports：`io.ktor.client.plugins.HttpTimeout`、`io.ktor.client.plugins.UserAgent`、`com.adbgui.desktop.platform.AppMeta`。

### Task 2: 旧 `.msi` 清理（downloader）
- Modify: `KtorUpdateDownloader.kt`
- `download()` 开始前删 `updates/` 下所有旧 `.msi`（除当前正在下的 sha，避免同名冲突；`.part` 已各自清理）。

### Task 3: 无 Content-Length 时的进度（downloader + VM state + UI）
- Modify: `KtorUpdateDownloader.kt`（total<0 时 `onProgress(-1f)` 信号 indeterminate）、`UpdateViewModel`/`UpdateState.Downloading(progress: Float)`（-1=indeterminate）、`SettingsScreen`（progress<0 用 `LinearProgressIndicator()` 无参 indeterminate）。

### Task 4: 并发 `checkForUpdates` 去重（VM）
- Modify: `UpdateViewModel.kt`
- 加 `@Volatile private var checkJob: Job?`；`checkForUpdates()` 仿 `downloadUpdate` 的 `downloadJob` 模式：active 则 return 现有 job。

### Task 5: `PortableUpdateNotifier` URL 引号
- Modify: `PortableUpdateNotifier.kt`
- `start "" "<url>"`——给 url 加引号防 `&` 被当命令分隔。

### Task 6: `UpdateVersion` leading zeros + 非数字 prerelease 测试
- Modify: `core/.../domain/UpdateVersion.kt`（regex `\d+` → `(0|[1-9]\d*)` 拒 leading zeros）+ `UpdateVersionTest.kt`（加 `rejects_leading_zeros` + `prerelease_lexical_beta_vs_rc` 测试）。

### Task 7: 源感知 MSI url 代理改写
- Modify: `core/.../update/UpdateSource.kt`（加 `val proxyPrefix: String? = null`）+ `UpdateSourceRegistry.kt`（github-mirror 设 `proxyPrefix = "https://gh-proxy.com/"`）+ `UpdateSourceRegistryTest`（断言 mirror 有 proxyPrefix）。
- Modify: `UpdateViewModel.kt` `downloadUpdate()`：读 source（已解析），若 `source.proxyPrefix != null` 则 `effectiveUrl = source.proxyPrefix + manifest.url`，否则 `manifest.url`，传给 `downloader.download(effectiveUrl, ...)`。VM 需在 Available 时记住 source（`@Volatile private var lastSource: UpdateSource?`）或从 settings 重新解析。
- 这样 manifest 里 `url` 可写直连 GitHub（全球通用），镜像源自动代理 MSI 下载。
- Test: `UpdateViewModelTest` 用 fake downloader 记录收到的 url，断言镜像源时收到 proxied url。

### Task 8: `UpdateState.Error` typed（raw + 折叠 UI）
- Modify: `UpdateViewModel.kt`/`UpdateState`（`Error(val message: String, val raw: String? = null)`）+ `UpdateChecker`/`UpdateDownloadResult`→VM 映射保留 raw（parse error 传 `raw`；download error 不传）+ `SettingsScreen` UI（message + 可折叠 `raw` 详情，仿 adb 错误折叠）。
- 注：cause（Throwable）不放进 UI state（logger 已记）；只 raw 字符串进 state。

## 自检

- Spec 覆盖：§8 超时（Task 1）、原文兜底（Task 8）、清理（Task 2）、可取消（Task 3/4 并发/进度）、sha256（已 stage 2）、仅 HTTPS（已 stage 1）。
- Placeholder：无。
- 类型一致：`UpdateSource.proxyPrefix` / `UpdateState.Error(message, raw)` / `UpdateState.Downloading(progress=-1=indeterminate)` / `checkJob`/`downloadJob` —— 跨任务签名一致。

## 执行交接

计划存 `docs/superpowers/plans/2026-09-08-auto-update-hardening.md`。Subagent-Driven，worktree `auto-update-hardening` 从 master HEAD（ae9aeaa）开。
