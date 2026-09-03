# ADB GUI 在线更新 — 设计文档

- **日期**：2026-09-03
- **状态**：草案，待用户审阅
- **作者**：brainstorming 协作产出
- **前置**：延续 `2026-08-14-adb-gui-design.md` 的分层与红线，新增"更新"子系统

## 1. 背景与目标

ADB GUI 已分发为 MSI（perUserInstall 每用户安装，免提权）与 AppImage 便携版。当前用户获取新版需手动到发布页下载重装。本功能在应用内实现"检查更新 → 下载 → 校验 → 触发升级"，降低升级摩擦。

- **目标**：MSI 安装版支持一键自更新；便携版支持"检测到新版 + 跳浏览器下载"。
- **非目标**：增量更新（只换改动 jar）、差分包；便携版运行中自替换目录；强制更新锁定。

## 2. 关键决策

| 维度 | 决策 |
|---|---|
| 更新机制 | **MSI 自更新**：查 manifest → 下载 MSI → sha256 校验 → `msiexec /i` 升级（perUserInstall 免提权）→ 旧进程退出 |
| 便携版策略 | 仅检测 + 跳浏览器下载页，不自替换 |
| 托管源 | **GitHub Releases 为默认真相源**；设置页可切换"源"；源列表由开发者内置常量维护 |
| Manifest | 每 source 同格式 JSON，随 release 资产分发（`latest.json`） |
| 安全 | 仅 HTTPS；sha256 强校验失败即删；版本不降级 |
| 依赖 | 复用已引入的 Ktor CIO，不引入更新框架（Update4j 等） |
| 频率 | 启动后后台静默检查一次（可设置里关）；设置页可手动检查 |

## 3. 源（UpdateSource）模型

开发者内置常量列表，用户在设置页下拉选其一，持久化到 `settings.json`。**源列表不在运行时远程拉取**——避免"源被劫持改源"的安全面；新源随 app 版本升级在常量里增删。

```kotlin
data class UpdateSource(
    val id: String,          // "github-official"
    val displayName: String, // "GitHub 官方"
    val manifestUrl: String, // https 直链到 latest.json
)
```

初始内置列表（开发者维护，发版时改）：

- `github-official` → GitHub Releases `latest.json` 直链
- `github-mirror` → ghproxy 镜像直链（国内加速，可选）
- `oss-mirror` → 开发者自建 OSS/COS（留位，按需启用）

默认选中 `github-official`。

## 4. Manifest 格式

```json
{
  "version": "1.1.0",
  "url": "https://.../AdbGui-1.1.0.msi",
  "sha256": "<lowercase 64 hex>",
  "size": 52428800,
  "notes": "修复 scrcpy 启动…",
  "minAppVersion": "1.0.0"
}
```

- `version`：语义版本，`UpdateVersionComparer` 比较。
- `sha256`：下载后强校验；不符即删文件 + 内联报错。
- `minAppVersion`：可选，低于该版本要求用户走整包重装而非跳级（先 v1 留字段，逻辑后续再细化）。
- 解析失败保留原文兜底（遵循错误处理约定：不静默吞）。

## 5. 架构落点（守红线）

```
:core
  domain/UpdateManifest.kt          data class
  domain/UpdateSource.kt            data class
  domain/UpdateVersion.kt           语义版本比较纯逻辑
  update/UpdateManifestParser.kt    纯函数 object，JSON→UpdateManifest
  update/UpdateVersionComparer.kt   当前 vs 远程 比较
  update/UpdateSourceRegistry.kt    内置常量源列表
  update/UpdateChecker.kt           HTTP 拉 manifest（注入 HttpClient + io Dispatcher + Logger）
  update/UpdateDownloadResult.kt    sealed: Success(path)/HashMismatch/NetworkError/...

:desktop/platform
  UpdateDownloader.kt   下载 MSI 到 %APPDATA%/AdbGui/updates/<version>.msi.part→原子重命名，校验 sha256
  MsiUpgrader.kt        调 msiexec /i 启动安装，返回需要退出码
  （便携版）PortableUpdateNotifier.kt  仅打开下载页 URL

:desktop
  ui/settings/UpdateSettingsSection.kt   源下拉 + 手动检查 + 上次检查时间/错误
  ui/UpdateBanner.kt                      主窗口内联更新条（不抢焦、不弹模态）
  viewmodel/UpdateViewModel.kt            StateFlow 状态机
```

- **架构红线符合性**：
  1. `:core` 不依赖 UI —— 更新检查/解析/版本比较全在 `:core`，纯 Kotlin 可单测。
  2. UI 不直接碰网络/进程 —— `UpdateViewModel` 只读 `UpdateViewModel.state: StateFlow`、回调 `UpdateChecker`/`UpdateDownloader`/`MsiUpgrader`。
  3. `:core` 不起真 HTTP —— 走注入的 `HttpClient`（Ktor），测试用 mock engine。
  4. 平台差异（msiexec / `%APPDATA%`）藏 `:desktop/platform`。
  5. 解析与执行分离 —— `UpdateManifestParser` 纯函数，`UpdateChecker` 调它。

## 6. UpdateViewModel 状态机

```
Idle → Checking → (NoUpdate | Available(manifest) | Error)
Available → Downloading(progress) → (Ready(path) | DownloadError | Cancelled)
Ready → Installing(triggered) → 退出进程 → 外部 msiexec 升级
```

- 启动后台静默检查失败 → `Error`，不打断用户，设置页可见。
- 便携版 `Available` 的"下载并安装"按钮文案改为"打开下载页"，调 `PortableUpdateNotifier`。
- 更新提示条：主窗口顶部内联条，可折叠，不抢焦点（符合"错误本地化不弹模态"约定）。

## 7. 数据流

1. 启动（或用户在设置页点"检查更新"）→ `UpdateViewModel.checkForUpdates()`
2. `UpdateChecker.fetch(currentSource.manifestUrl)` → `UpdateManifestParser.parse(json)` → `UpdateVersionComparer.isNewer(remote, currentVersion)`
3. 有新版 → `state = Available(manifest)` → UI 显示更新条
4. 用户点"立即更新" → `UpdateDownloader.download(manifest.url, manifest.sha256)` 流式写 `.part` → 完成重命名 → sha256 校验
5. 校验通过 → `MsiUpgrader.launch(msiPath)` → `state = Installing` → `MainKt` 退出进程 → msiexec 静默/向导升级
6. 失败任一步 → 内联 `Error` + adb 原文/原因折叠展开，不删已下好的包除非校验失败

## 8. 安全与健壮性

- 仅 HTTPS 源；非 https 源在 `UpdateSourceRegistry` 里不收录。
- sha256 强校验失败 → 删 `.part`/完成文件 + `HashMismatch` 错误。
- 版本不降级：`version <= currentVersion` → `NoUpdate`，即使 manifest 被改低。
- 下载原子写：`<version>.msi.part` → `ATOMIC_MOVE` 重命名（遵循持久化约定）。
- 检查/下载均可取消（注入的 scope 协程可取消）。
- 不记敏感数据：manifest URL、版本号记 INFO；下载进度记 DEBUG 级，不含包内容。

## 9. 测试策略

`:core` TDD（先写失败测试 → 最小实现）：

- `UpdateManifestParser`：合法 manifest、缺字段、sha 格式错、version 非语义版本 → fixture 放 `core/src/test/resources/fixtures/update/`。
- `UpdateVersionComparer`：`1.0.0` vs `1.1.0`、`1.1.0` vs `1.1.0-beta`、降级判定、预发布版本比较多变体。
- `UpdateChecker`：注入 Ktor `MockEngine`，覆盖 200/404/超时/sha 不符。
- `UpdateSourceRegistry`：默认源 = github-official、列表去重、id 唯一。

`:desktop` ViewModel 单测：状态机 `Idle→Checking→Available→Downloading→Ready`、取消、错误路径、便携版分支。

## 10. 设置与持久化

`settings.json` 增字段：

```json
{
  "update": {
    "sourceId": "github-official",
    "checkOnStartup": true,
    "lastCheckAt": "2026-09-03T10:00:00Z",
    "lastCheckError": null
  }
}
```

原子写遵循现有 `SettingsStore` 约定（注入 `io` Dispatcher，`.tmp`→`ATOMIC_MOVE`）。

## 11. 分阶段交付

- **阶段 1**：`:core` 解析 + 版本比较 + checker + fixture；`:desktop` ViewModel 状态机单测；设置页源下拉 + 手动检查 + 展示新版信息。**不**做下载安装。
- **阶段 2**：`UpdateDownloader` + sha256 校验；MSI 版"下载并安装"走 `MsiUpgrader`；便携版"打开下载页"。
- **阶段 3**（可选）：启动后台静默检查、上次检查时间/错误展示、更新条折叠偏好持久化。

每阶段一个提交（Conventional Commits：`feat(core): update manifest parser` 等）。

## 12. 未决项

- 是否需要发版脚本自动生成 `latest.json` 并附到 GitHub Release（CI 自动化）——留到实现计划定。
- `minAppVersion` 的跳级限制逻辑 v1 是否实现，还是先只留字段。
- 国内镜像的具体 URL（ghproxy / 自建 OSS）由开发者在 `UpdateSourceRegistry` 填，本设计不锁死。
