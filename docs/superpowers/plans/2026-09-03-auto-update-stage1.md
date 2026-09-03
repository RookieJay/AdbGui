# ADB GUI 在线更新 — 实现计划（阶段 1：检查与展示）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在应用内实现"检查更新 → 展示新版信息"，MSI 版与便携版均支持；本阶段**不**含下载/安装（留待阶段 2）。

**Architecture:** `:core` 纯 Kotlin 实现版本比较、manifest 解析、源注册表、检查编排（通过注入的 `UpdateManifestFetcher` 接口，`:core` 不直接依赖 Ktor）；`:desktop/platform` 提供 Ktor 实现；`:desktop` 的 `UpdateViewModel` 用 `StateFlow` 暴露状态机，设置页增"更新源"下拉 + 手动检查，主窗口展示新版信息。HTTP 不直接落在 `:core`——比 spec §5 细化：`UpdateManifestFetcher` 接口放 `:core`，Ktor 实现放 `:desktop/platform`，与 `AdbProcessRunner` 模式一致。

**Tech Stack:** Kotlin 2.1.20 / KMP，kotlinx-serialization-json 1.7.3，kotlinx-coroutines 1.9.0，Ktor 3.0.3（仅 `:desktop`），Compose Multiplatform 1.7.3，Turbine 1.2.0（测试）。

**Spec:** `docs/superpowers/specs/2026-09-03-auto-update-design.md`（§3 源模型、§4 manifest、§5 架构落点、§6 状态机、§9 测试、§10 持久化、§11 阶段 1）。

## Global Constraints

- 红线：`:core` 不依赖 UI / Compose / `java.awt` / `javax.swing`；**本阶段 `:core` 也不引入 Ktor**（通过 `UpdateManifestFetcher` 接口隔离 HTTP，Ktor 实现在 `:desktop/platform`）。
- `:core` 所有 I/O 注入 `CoroutineDispatcher`（构造参数 `io: CoroutineDispatcher = Dispatchers.IO`），测试传 `Dispatchers.Unconfined`。
- 不留死代码：重构移除某用法时同时删声明；不许占位 `scope.launch{}`、未引用类/参数。
- 跨线程可变 `var` 必须 `@Volatile` / `Mutex` / `MutableStateFlow`。
- `:core` 一律 TDD：先写失败测试（含 fixture）→ 验证失败 → 最小实现 → 验证通过 → 提交。
- adb 输出 fixture 真实录制（本阶段无 adb 输出，manifest fixture 见 Task 2 说明）。
- Conventional Commits：`feat(core): ...` / `feat(desktop): ...` / `refactor: ...`。
- 命名包根：`com.adbgui.core.update.*`（`:core`）、`com.adbgui.desktop.update.*` / `com.adbgui.desktop.platform.*`（`:desktop`）。
- 用户偏好：回复中文，代码/commit 英文。
- 运行测试：`./gradlew :core:test` / `./desktop:test`；单个：`./gradlew :core:test --tests "com.adbgui.core.update.UpdateManifestParserTest"`。

## 文件结构

`:core`（新建包 `com.adbgui.core.update`）：
- `domain/UpdateVersion.kt` — 语义版本 data class + 解析。
- `update/UpdateManifest.kt` — manifest data class（@Serializable）。
- `update/UpdateManifestParser.kt` — 纯函数 object，JSON→UpdateManifest，失败抛 `UpdateManifestParseException`。
- `update/UpdateManifestParseException.kt` — 解析异常（带 raw 文本兜底）。
- `update/UpdateVersionComparer.kt` — object，`isNewer(remote, current): Boolean`。
- `update/UpdateSource.kt` — data class。
- `update/UpdateSourceRegistry.kt` — object，内置常量源列表 + `byId`。
- `update/UpdateManifestFetcher.kt` — interface（HTTP 隔离）。
- `update/UpdateCheckResult.kt` — sealed class。
- `update/UpdateChecker.kt` — 编排 fetcher→parser→comparer。

`:core` 测试 + fixture：
- `core/src/test/kotlin/com/adbgui/core/update/*Test.kt`
- `core/src/test/resources/fixtures/update/*.json`（manifest fixture）

`:desktop`：
- `platform/AppMeta.kt` — `const val APP_VERSION = "1.0.0"`（运行时版本真相源）。
- `platform/KtorUpdateManifestFetcher.kt` — Ktor 实现。
- `ui/update/UpdateViewModel.kt` — 状态机。
- `ui/update/UpdateViewModelTest.kt`
- 修改 `ui/SettingsViewModel.kt` / `ui/SettingsScreen.kt` — 源下拉 + 手动检查入口。
- 修改 `ui/i18n/Strings.kt` — 新键。
- 修改 `main/CompositionRoot.kt` — 装配。

`:core` `settings/SettingsStore.kt` — 增 `UpdateSettings` 字段。

---

### Task 1: `UpdateVersion` 语义版本解析 + 比较

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/domain/UpdateVersion.kt`
- Create: `core/src/test/kotlin/com/adbgui/core/domain/UpdateVersionTest.kt`

**Interfaces:**
- Produces: `data class UpdateVersion(val major: Int, val minor: Int, val patch: Int, val prerelease: String? = null)` with `companion fun parse(text: String): UpdateVersion`（非法抛 `IllegalArgumentException`）；`fun isGreaterThan(other: UpdateVersion): Boolean`。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.adbgui.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UpdateVersionTest {
    @Test fun parses_simple_triplet() {
        val v = UpdateVersion.parse("1.2.3")
        assertEquals(UpdateVersion(1, 2, 3), v)
        assertNull(v.prerelease)
    }
    @Test fun parses_with_prerelease() {
        val v = UpdateVersion.parse("1.0.0-beta.1")
        assertEquals(UpdateVersion(1, 0, 0, "beta.1"), v)
    }
    @Test fun rejects_garbage() {
        assertFailsWith<IllegalArgumentException> { UpdateVersion.parse("1.2") }
        assertFailsWith<IllegalArgumentException> { UpdateVersion.parse("x.y.z") }
    }
    @Test fun greater_than_major() {
        assertTrue(UpdateVersion.parse("2.0.0").isGreaterThan(UpdateVersion.parse("1.9.9")))
    }
    @Test fun greater_than_minor() {
        assertTrue(UpdateVersion.parse("1.10.0").isGreaterThan(UpdateVersion.parse("1.9.0")))
    }
    @Test fun equal_not_greater() {
        assertFalse(UpdateVersion.parse("1.0.0").isGreaterThan(UpdateVersion.parse("1.0.0")))
    }
    @Test fun prerelease_lower_than_release() {
        assertTrue(UpdateVersion.parse("1.0.0").isGreaterThan(UpdateVersion.parse("1.0.0-beta")))
        assertFalse(UpdateVersion.parse("1.0.0-beta").isGreaterThan(UpdateVersion.parse("1.0.0")))
    }
    @Test fun prerelease_order_by_identifier() {
        assertTrue(UpdateVersion.parse("1.0.0-rc.2").isGreaterThan(UpdateVersion.parse("1.0.0-rc.1")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.domain.UpdateVersionTest" -q`
Expected: FAIL — `UpdateVersion` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.adbgui.core.domain

/**
 * 语义版本（semver 子集）：MAJOR.MINOR.PATCH[-PRERELEASE]。
 * 仅支持点分数字 prerelease 段（beta.1, rc.2），不支持 build metadata。
 */
data class UpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String? = null,
) {
    fun isGreaterThan(other: UpdateVersion): Boolean {
        if (major != other.major) return major > other.major
        if (minor != other.minor) return minor > other.minor
        if (patch != other.patch) return patch > other.patch
        // 均有 prerelease：按段逐项比；无 prerelease 者 > 有 prerelease 者
        if (prerelease == null && other.prerelease == null) return false
        if (prerelease == null) return true              // release > prerelease
        if (other.prerelease == null) return false       // prerelease < release
        return comparePrerelease(prerelease, other.prerelease) > 0
    }

    private fun comparePrerelease(a: String, b: String): Int {
        val sa = a.split(".")
        val sb = b.split(".")
        val n = minOf(sa.size, sb.size)
        for (i in 0 until n) {
            val cmp = sa[i].toIntOrNull()?.let { x -> sb[i].toIntOrNull()?.let { y -> x.compareTo(y) } }
                ?: sa[i].compareTo(sb[i])
            if (cmp != 0) return cmp
        }
        return sa.size.compareTo(sb.size)
    }

    companion object {
        private val re = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.\-]+))?$""")
        fun parse(text: String): UpdateVersion {
            val m = re.matchEntire(text.trim())
            require(m != null) { "Invalid version: $text" }
            return UpdateVersion(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
                m.groupValues[4].takeIf { it.isNotEmpty() },
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.domain.UpdateVersionTest" -q`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/domain/UpdateVersion.kt core/src/test/kotlin/com/adbgui/core/domain/UpdateVersionTest.kt
git commit -m "feat(core): add UpdateVersion semver parse + compare"
```

---

### Task 2: `UpdateManifest` + `UpdateManifestParser` + fixtures

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateManifest.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateManifestParseException.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateManifestParser.kt`
- Create: `core/src/test/kotlin/com/adbgui/core/update/UpdateManifestParserTest.kt`
- Create: `core/src/test/resources/fixtures/update/manifest_valid.json`
- Create: `core/src/test/resources/fixtures/update/manifest_missing_sha.json`
- Create: `core/src/test/resources/fixtures/update/manifest_bad_sha.json`
- Create: `core/src/test/resources/fixtures/update/manifest_bad_version.json`

**Interfaces:**
- Consumes: `UpdateVersion`（Task 1）。
- Produces: `@Serializable data class UpdateManifest(version, url, sha256, size, notes, minAppVersion)`；`object UpdateManifestParser { fun parse(json: String): UpdateManifest }` 抛 `UpdateManifestParseException(raw, reason)`。

> **Fixture 说明**：manifest 不是 adb 输出，CLAUDE.md §技术债 4 不强制真机录制；但 fixture 仍要标明来源。每个 fixture 头行 `// 来自 <来源>` 注释说明其为构造示例 + 构造日期 + 对应 manifest 字段，便于复核。

- [ ] **Step 1: Create fixtures**

`core/src/test/resources/fixtures/update/manifest_valid.json`：
```json
// 构造示例（2026-09-03），对应 §4 manifest 字段；version 1.1.0，sha256 为占位 64 hex
{
  "version": "1.1.0",
  "url": "https://example.com/AdbGui-1.1.0.msi",
  "sha256": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2",
  "size": 52428800,
  "notes": "修复 scrcpy 启动",
  "minAppVersion": "1.0.0"
}
```

`manifest_missing_sha.json`：
```json
// 构造示例（2026-09-03）：缺 sha256 字段，用于测试解析失败
{
  "version": "1.1.0",
  "url": "https://example.com/AdbGui-1.1.0.msi"
}
```

`manifest_bad_sha.json`：
```json
// 构造示例（2026-09-03）：sha256 非 64 hex
{
  "version": "1.1.0",
  "url": "https://example.com/AdbGui-1.1.0.msi",
  "sha256": "tooshort"
}
```

`manifest_bad_version.json`：
```json
// 构造示例（2026-09-03）：version 非语义版本
{
  "version": "1.1",
  "url": "https://example.com/AdbGui-1.1.0.msi",
  "sha256": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2"
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.adbgui.core.update

import com.adbgui.core.domain.UpdateVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UpdateManifestParserTest {
    private fun readFixture(name: String): String =
        UpdateManifestParserTest::class.java.getResourceAsStream("/fixtures/update/$name")!!
            .bufferedReader().use { it.readText() }

    @Test fun parses_valid_manifest() {
        val m = UpdateManifestParser.parse(readFixture("manifest_valid.json"))
        assertEquals("1.1.0", m.version)
        assertEquals("https://example.com/AdbGui-1.1.0.msi", m.url)
        assertEquals(64, m.sha256.length)
        assertEquals(52428800, m.size)
        assertEquals("修复 scrcpy 启动", m.notes)
        assertEquals("1.0.0", m.minAppVersion)
    }

    @Test fun throws_on_missing_sha() {
        val raw = readFixture("manifest_missing_sha.json")
        val ex = assertFailsWith<UpdateManifestParseException> { UpdateManifestParser.parse(raw) }
        assertEquals(raw, ex.raw)
    }

    @Test fun throws_on_bad_sha() {
        assertFailsWith<UpdateManifestParseException> {
            UpdateManifestParser.parse(readFixture("manifest_bad_sha.json"))
        }
    }

    @Test fun throws_on_bad_version() {
        assertFailsWith<UpdateManifestParseException> {
            UpdateManifestParser.parse(readFixture("manifest_bad_version.json"))
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.update.UpdateManifestParserTest" -q`
Expected: FAIL — `UpdateManifestParser` unresolved.

- [ ] **Step 4: Write minimal implementation**

`UpdateManifest.kt`：
```kotlin
package com.adbgui.core.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long? = null,
    val notes: String? = null,
    val minAppVersion: String? = null,
)
```

`UpdateManifestParseException.kt`：
```kotlin
package com.adbgui.core.update

class UpdateManifestParseException(
    val raw: String,
    reason: String,
    cause: Throwable? = null,
) : RuntimeException("Invalid update manifest: $reason", cause)
```

`UpdateManifestParser.kt`：
```kotlin
package com.adbgui.core.update

import com.adbgui.core.domain.UpdateVersion
import kotlinx.serialization.json.Json

object UpdateManifestParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): UpdateManifest {
        val m = runCatching { json.decodeFromString<UpdateManifest>(text) }
            .getOrElse { throw UpdateManifestParseException(text, "json decode failed", it) }
        validate(m, text)
        return m
    }

    private fun validate(m: UpdateManifest, raw: String) {
        requireField(m.version.isNotBlank()) { "version" } ?: throw fail(raw, "version missing")
        runCatching { UpdateVersion.parse(m.version) }.onFailure { throw fail(raw, "version not semver") }
        requireField(m.url.isNotBlank()) { "url" } ?: throw fail(raw, "url missing")
        requireField(m.sha256.length == 64 && m.sha256.all { it.lowercaseChar() in '0'..'9' || it in 'a'..'f' }) {
            "sha256"
        } ?: throw fail(raw, "sha256 must be 64 lowercase hex")
    }

    private fun requireField(cond: Boolean): Boolean? = if (cond) true else null
    private fun fail(raw: String, reason: String): UpdateManifestParseException =
        UpdateManifestParseException(raw, reason)
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.update.UpdateManifestParserTest" -q`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/update/ core/src/test/kotlin/com/adbgui/core/update/UpdateManifestParserTest.kt core/src/test/resources/fixtures/update/
git commit -m "feat(core): add UpdateManifest + parser with fixtures"
```

---

### Task 3: `UpdateVersionComparer`

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateVersionComparer.kt`
- Create: `core/src/test/kotlin/com/adbgui/core/update/UpdateVersionComparerTest.kt`

**Interfaces:**
- Consumes: `UpdateVersion`（Task 1）。
- Produces: `object UpdateVersionComparer { fun isNewer(remote: String, current: String): Boolean }`（非法 version 抛 `IllegalArgumentException`，由 caller 决定兜底）。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.adbgui.core.update

import com.adbgui.core.domain.UpdateVersion
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateVersionComparerTest {
    @Test fun remote_newer_minor() = assertTrue(UpdateVersionComparer.isNewer("1.1.0", "1.0.0"))
    @Test fun remote_newer_patch() = assertTrue(UpdateVersionComparer.isNewer("1.0.1", "1.0.0"))
    @Test fun remote_older() = assertFalse(UpdateVersionComparer.isNewer("1.0.0", "1.1.0"))
    @Test fun equal() = assertFalse(UpdateVersionComparer.isNewer("1.0.0", "1.0.0"))
    @Test fun remote_prerelease_vs_release() = assertFalse(UpdateVersionComparer.isNewer("1.0.0-beta", "1.0.0"))
    @Test fun rejects_bad_remote() = assertFailsWith<IllegalArgumentException> {
        UpdateVersionComparer.isNewer("1.0", "1.0.0")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.update.UpdateVersionComparerTest" -q`
Expected: FAIL — unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.adbgui.core.update

import com.adbgui.core.domain.UpdateVersion

object UpdateVersionComparer {
    fun isNewer(remote: String, current: String): Boolean =
        UpdateVersion.parse(remote).isGreaterThan(UpdateVersion.parse(current))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.update.UpdateVersionComparerTest" -q`
Expected: PASS (6).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/update/UpdateVersionComparer.kt core/src/test/kotlin/com/adbgui/core/update/UpdateVersionComparerTest.kt
git commit -m "feat(core): add UpdateVersionComparer"
```

---

### Task 4: `UpdateSource` + `UpdateSourceRegistry`

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateSource.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateSourceRegistry.kt`
- Create: `core/src/test/kotlin/com/adbgui/core/update/UpdateSourceRegistryTest.kt`

**Interfaces:**
- Produces: `data class UpdateSource(val id: String, val displayName: String, val manifestUrl: String)`；`object UpdateSourceRegistry { val all: List<UpdateSource>; val default: UpdateSource; fun byId(id: String): UpdateSource? }`。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.adbgui.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateSourceRegistryTest {
    @Test fun default_is_github_official() {
        assertEquals("github-official", UpdateSourceRegistry.default.id)
    }
    @Test fun byId_finds_existing() {
        assertNotNull(UpdateSourceRegistry.byId("github-official"))
    }
    @Test fun byId_returns_null_for_unknown() {
        assertNull(UpdateSourceRegistry.byId("nope"))
    }
    @Test fun ids_are_unique() {
        val ids = UpdateSourceRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
    @Test fun all_urls_are_https() {
        UpdateSourceRegistry.all.forEach { assertTrue(it.manifestUrl.startsWith("https://"), "${it.id} not https") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.update.UpdateSourceRegistryTest" -q`
Expected: FAIL — unresolved.

- [ ] **Step 3: Write minimal implementation**

`UpdateSource.kt`：
```kotlin
package com.adbgui.core.update

data class UpdateSource(
    val id: String,
    val displayName: String,
    val manifestUrl: String,
)
```

`UpdateSourceRegistry.kt`：
```kotlin
package com.adbgui.core.update

/**
 * 开发者维护的内置更新源常量列表。用户在设置页从下拉选其一；
 * 不在运行时远程拉取源列表（防劫持）。新源随 app 版本在此增删。
 */
object UpdateSourceRegistry {
    val all: List<UpdateSource> = listOf(
        UpdateSource(
            "github-official",
            "GitHub 官方",
            "https://github.com/OWNER/ADBGUI/releases/latest/download/latest.json",
        ),
        UpdateSource(
            "github-mirror",
            "GitHub 镜像 (ghproxy)",
            "https://ghproxy.com/https://github.com/OWNER/ADBGUI/releases/latest/download/latest.json",
        ),
    )

    val default: UpdateSource = all.first { it.id == "github-official" }

    fun byId(id: String): UpdateSource? = all.firstOrNull { it.id == id }
}
```

> 注：`OWNER/ADBGUI` 与 `ghproxy.com` 为占位，发布前由开发者填真实仓库与镜像；本阶段先保证结构正确。**不要**留 TODO 注释以外的东西——这是结构占位，发布前必填。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.update.UpdateSourceRegistryTest" -q`
Expected: PASS (5).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/update/UpdateSource.kt core/src/main/kotlin/com/adbgui/core/update/UpdateSourceRegistry.kt core/src/test/kotlin/com/adbgui/core/update/UpdateSourceRegistryTest.kt
git commit -m "feat(core): add UpdateSource + registry"
```

---

### Task 5: `UpdateManifestFetcher` interface + `UpdateCheckResult` + `UpdateChecker`

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateManifestFetcher.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateCheckResult.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/update/UpdateChecker.kt`
- Create: `core/src/test/kotlin/com/adbgui/core/update/UpdateCheckerTest.kt`

**Interfaces:**
- Consumes: `UpdateManifestParser`（Task 2）、`UpdateVersionComparer`（Task 3）、`UpdateSource`（Task 4）、`Logger`（`com.adbgui.core.log`）。
- Produces: `interface UpdateManifestFetcher { suspend fun fetch(url: String): String }`；`sealed class UpdateCheckResult { object NoUpdate; data class UpdateAvailable(manifest); data class Error(message, raw, cause) }`；`class UpdateChecker(fetcher, currentVersion, logger) { suspend fun check(source): UpdateCheckResult }`。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.adbgui.core.update

import com.adbgui.core.log.InMemoryLogger
import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private val src = UpdateSource("github-official", "GitHub 官方", "https://example.com/latest.json")
    private val log = InMemoryLogger(LogLevel.DEBUG) { 0L }

    private fun manifest(version: String): String = """
        {
          "version": "$version",
          "url": "https://example.com/AdbGui-$version.msi",
          "sha256": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2"
        }
    """.trimIndent()

    private class FakeFetcher(private val textFor: (String) -> String?) : UpdateManifestFetcher {
        override suspend fun fetch(url: String): String =
            textFor(url) ?: error("no stub for $url")
    }

    @Test fun available_when_remote_newer() = runTest {
        val checker = UpdateChecker(FakeFetcher { manifest("1.1.0") }, "1.0.0", log)
        val r = checker.check(src)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertEquals("1.1.0", r.manifest.version)
    }

    @Test fun no_update_when_equal() = runTest {
        val checker = UpdateChecker(FakeFetcher { manifest("1.0.0") }, "1.0.0", log)
        assertIs<UpdateCheckResult.NoUpdate>(checker.check(src))
    }

    @Test fun no_update_when_remote_older() = runTest {
        val checker = UpdateChecker(FakeFetcher { manifest("0.9.0") }, "1.0.0", log)
        assertIs<UpdateCheckResult.NoUpdate>(checker.check(src))
    }

    @Test fun error_when_fetch_throws() = runTest {
        val checker = UpdateChecker(FakeFetcher { null }, "1.0.0", log)
        val r = checker.check(src)
        assertIs<UpdateCheckResult.Error>(r)
        assertTrue(r.message.contains("fetch"))
    }

    @Test fun error_when_manifest_invalid() = runTest {
        val checker = UpdateChecker(FakeFetcher { "{not json" }, "1.0.0", log)
        val r = checker.check(src)
        assertIs<UpdateCheckResult.Error>(r)
        assertEquals("{not json", r.raw)
    }

    @Test fun logs_check_at_info() = runTest {
        val checker = UpdateChecker(FakeFetcher { manifest("1.1.0") }, "1.0.0", log)
        checker.check(src)
        assertTrue(log.entries.any { it.message.contains("update") && it.level == LogLevel.INFO })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.update.UpdateCheckerTest" -q`
Expected: FAIL — unresolved.

- [ ] **Step 3: Write minimal implementation**

`UpdateManifestFetcher.kt`：
```kotlin
package com.adbgui.core.update

interface UpdateManifestFetcher {
    suspend fun fetch(url: String): String
}
```

`UpdateCheckResult.kt`：
```kotlin
package com.adbgui.core.update

sealed class UpdateCheckResult {
    object NoUpdate : UpdateCheckResult()
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateCheckResult()
    data class Error(
        val message: String,
        val raw: String? = null,
        val cause: Throwable? = null,
    ) : UpdateCheckResult()
}
```

`UpdateChecker.kt`：
```kotlin
package com.adbgui.core.update

import com.adbgui.core.log.Logger

class UpdateChecker(
    private val fetcher: UpdateManifestFetcher,
    private val currentVersion: String,
    private val logger: Logger,
) {
    suspend fun check(source: UpdateSource): UpdateCheckResult {
        logger.info("update: checking source=${source.id} current=$currentVersion")
        val raw = try {
            fetcher.fetch(source.manifestUrl)
        } catch (t: Throwable) {
            logger.warn("update: fetch failed source=${source.id}", t)
            return UpdateCheckResult.Error("fetch failed: ${t.message}")
        }
        val manifest = try {
            UpdateManifestParser.parse(raw)
        } catch (e: UpdateManifestParseException) {
            logger.warn("update: manifest parse failed source=${source.id}", e)
            return UpdateCheckResult.Error("invalid manifest", raw = e.raw, cause = e)
        }
        return if (UpdateVersionComparer.isNewer(manifest.version, currentVersion)) {
            logger.info("update: available ${manifest.version}")
            UpdateCheckResult.UpdateAvailable(manifest)
        } else {
            logger.info("update: no update (remote=${manifest.version})")
            UpdateCheckResult.NoUpdate
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.update.UpdateCheckerTest" -q`
Expected: PASS (6).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/update/UpdateManifestFetcher.kt core/src/main/kotlin/com/adbgui/core/update/UpdateCheckResult.kt core/src/main/kotlin/com/adbgui/core/update/UpdateChecker.kt core/src/test/kotlin/com/adbgui/core/update/UpdateCheckerTest.kt
git commit -m "feat(core): add UpdateChecker orchestrator + fetcher interface"
```

---

### Task 6: `UpdateSettings` 持久化字段

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt`
- Modify: `core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `Settings.update: UpdateSettings`（`sourceId: String = "github-official"`, `checkOnStartup: Boolean = true`, `lastCheckAt: String? = null`, `lastCheckError: String? = null`）。

- [ ] **Step 1: Write the failing test**

Add to `SettingsStoreTest.kt`：
```kotlin
@Test
fun update_settings_default_and_round_trip() = runTest {
    val dir = Files.createTempDirectory("upd")
    val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
    val default = store.load()
    assertEquals("github-official", default.update.sourceId)
    assertTrue(default.update.checkOnStartup)
    assertNull(default.update.lastCheckAt)
    store.save(default.copy(update = default.update.copy(sourceId = "github-mirror", lastCheckAt = "2026-09-03T10:00:00Z")))
    val reloaded = store.load()
    assertEquals("github-mirror", reloaded.update.sourceId)
    assertEquals("2026-09-03T10:00:00Z", reloaded.update.lastCheckAt)
}
```
（`import com.adbgui.core.settings.UpdateSettings` 或直接经 `Settings.update` 访问，按实现决定。）

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.settings.SettingsStoreTest" -q`
Expected: FAIL — `update` unresolved。

- [ ] **Step 3: Write minimal implementation**

In `SettingsStore.kt` add：
```kotlin
@Serializable
data class UpdateSettings(
    val sourceId: String = "github-official",
    val checkOnStartup: Boolean = true,
    val lastCheckAt: String? = null,
    val lastCheckError: String? = null,
)
```
and add field to `Settings`:
```kotlin
val update: UpdateSettings = UpdateSettings(),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.settings.SettingsStoreTest" -q`
Expected: PASS（含新测试 + 既有全过——`ignoreUnknownKeys=true` 保证旧 settings.json 兼容）。

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt
git commit -m "feat(core): add UpdateSettings to Settings"
```

---

### Task 7: `AppMeta` 运行时版本常量

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/platform/AppMeta.kt`

**Interfaces:**
- Produces: `object AppMeta { const val APP_VERSION = "1.0.0" }`。**必须**与 `desktop/build.gradle.kts:25` 的 `packageVersion` 保持同步（在两处都加注释互指）。

- [ ] **Step 1: Write implementation**

```kotlin
package com.adbgui.desktop.platform

/**
 * 运行时版本真相源。更新检查用此比对 manifest.version。
 * 改版本时同步改 desktop/build.gradle.kts 的 packageVersion（两处需一致）。
 */
object AppMeta {
    const val APP_VERSION = "1.0.0"
}
```

- [ ] **Step 2: Add cross-reference comment in build.gradle.kts**

Edit `desktop/build.gradle.kts:25`：
```kotlin
    packageVersion = "1.0.0"  // keep in sync with AppMeta.APP_VERSION
```

- [ ] **Step 3: Build sanity check**

Run: `./gradlew :desktop:compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/platform/AppMeta.kt desktop/build.gradle.kts
git commit -m "feat(desktop): add AppMeta runtime version constant"
```

---

### Task 8: `KtorUpdateManifestFetcher` 实现

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/platform/KtorUpdateManifestFetcher.kt`

**Interfaces:**
- Consumes: `UpdateManifestFetcher`（Task 5）、Ktor HttpClient（`io.ktor:ktor-client-cio`，已在 `desktop/build.gradle.kts:14`）。
- Produces: `class KtorUpdateManifestFetcher(io: CoroutineDispatcher) : UpdateManifestFetcher`。

> 不写单测——HTTP 行为由 `UpdateCheckerTest`（Task 5）用 `FakeFetcher` 覆盖逻辑；真实 Ktor 行为靠阶段 2 的集成验证。避免引入 `ktor-client-mock` 依赖。

- [ ] **Step 1: Write implementation**

```kotlin
package com.adbgui.desktop.platform

import com.adbgui.core.update.UpdateManifestFetcher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KtorUpdateManifestFetcher(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateManifestFetcher {
    private val client = HttpClient()

    override suspend fun fetch(url: String): String = withContext(io) {
        client.get(url).bodyAsText()
    }
}
```

- [ ] **Step 2: Build sanity check**

Run: `./gradlew :desktop:compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/platform/KtorUpdateManifestFetcher.kt
git commit -m "feat(desktop): add KtorUpdateManifestFetcher impl"
```

---

### Task 9: `UpdateViewModel` 状态机

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/update/UpdateViewModel.kt`
- Create: `desktop/src/test/kotlin/com/adbgui/desktop/ui/update/UpdateViewModelTest.kt`

**Interfaces:**
- Consumes: `UpdateChecker`（Task 5）、`UpdateSourceRegistry`（Task 4）、`SettingsStore`（Task 6）、`AppMeta.APP_VERSION`（Task 7）、`CoroutineScope`。
- Produces: `class UpdateViewModel(checker, store, scope)` with `val state: StateFlow<UpdateState>`、`fun checkForUpdates()`、`fun selectSource(id: String)`。`sealed class UpdateState { Idle; Checking; NoUpdate; Available(version, notes, url); Error(message) }`。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.adbgui.desktop.ui.update

import com.adbgui.core.log.NoopLogger
import com.adbgui.core.settings.SettingsStore
import com.adbgui.core.update.UpdateCheckResult
import com.adbgui.core.update.UpdateChecker
import com.adbgui.core.update.UpdateManifest
import com.adbgui.core.update.UpdateManifestFetcher
import com.adbgui.core.update.UpdateSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateViewModelTest {
    private class FakeFetcher(private val text: String?) : UpdateManifestFetcher {
        override suspend fun fetch(url: String): String = text ?: error("no stub")
    }

    private fun vm(scope: TestScope, fetcherText: String?, current: String = "1.0.0"): Triple<UpdateViewModel, SettingsStore, UpdateChecker> {
        val dir = Files.createTempDirectory("uvm")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val checker = UpdateChecker(FakeFetcher(fetcherText), current, NoopLogger)
        return Triple(UpdateViewModel(checker, store, scope), store, checker)
    }

    @Test fun check_finds_update() = runTest {
        val (vm, _, _) = vm(this, """
            {"version":"1.1.0","url":"https://x/y.msi",
             "sha256":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2"}
        """.trimIndent())
        vm.checkForUpdates()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("1.1.0", (s as UpdateState.Available).version)
    }

    @Test fun check_no_update() = runTest {
        val (vm, _, _) = vm(this, """
            {"version":"1.0.0","url":"https://x/y.msi",
             "sha256":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2"}
        """.trimIndent())
        vm.checkForUpdates()
        advanceUntilIdle()
        assertEquals(UpdateState.NoUpdate, vm.state.value)
    }

    @Test fun check_error_sets_error_state() = runTest {
        val (vm, _, _) = vm(this, null)
        vm.checkForUpdates()
        advanceUntilIdle()
        assert(vm.state.value is UpdateState.Error)
    }

    @Test fun select_source_persists() = runTest {
        val (vm, store, _) = vm(this, null)
        vm.selectSource("github-mirror")
        advanceUntilIdle()
        assertEquals("github-mirror", store.load().update.sourceId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.update.UpdateViewModelTest" -q`
Expected: FAIL — unresolved.

- [ ] **Step 3: Write minimal implementation**

`UpdateViewModel.kt`：
```kotlin
package com.adbgui.desktop.ui.update

import com.adbgui.core.settings.SettingsStore
import com.adbgui.core.update.UpdateCheckResult
import com.adbgui.core.update.UpdateChecker
import com.adbgui.core.update.UpdateSourceRegistry
import com.adbgui.desktop.platform.AppMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object NoUpdate : UpdateState()
    data class Available(val version: String, val notes: String?, val url: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateViewModel(
    private val checker: UpdateChecker,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    fun checkForUpdates() = scope.launch {
        _state.value = UpdateState.Checking
        val settings = store.load()
        val source = UpdateSourceRegistry.byId(settings.update.sourceId) ?: UpdateSourceRegistry.default
        val result = checker.check(source)
        val nowIso = java.time.Instant.now().toString()
        when (result) {
            is UpdateCheckResult.NoUpdate -> {
                _state.value = UpdateState.NoUpdate
                persistResult(settings.update.sourceId, nowIso, null)
            }
            is UpdateCheckResult.UpdateAvailable -> {
                val m = result.manifest
                _state.value = UpdateState.Available(m.version, m.notes, m.url)
                persistResult(settings.update.sourceId, nowIso, null)
            }
            is UpdateCheckResult.Error -> {
                _state.value = UpdateState.Error(result.message)
                persistResult(settings.update.sourceId, nowIso, result.message)
            }
        }
    }

    fun selectSource(id: String) = scope.launch {
        store.update { it.copy(update = it.update.copy(sourceId = id)) }
    }

    private suspend fun persistResult(sourceId: String, at: String, err: String?) {
        store.update { it.copy(update = it.update.copy(lastCheckAt = at, lastCheckError = err)) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.update.UpdateViewModelTest" -q`
Expected: PASS (4).

- [ ] **Step 5: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/update/UpdateViewModel.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/update/UpdateViewModelTest.kt
git commit -m "feat(desktop): add UpdateViewModel state machine"
```

---

### Task 10: 设置页 UI 接入 + i18n 键 + CompositionRoot 装配

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/CompositionRoot.kt`

**Interfaces:**
- Consumes: `UpdateViewModel`（Task 9）、`UpdateSourceRegistry`（Task 4）、`AppMeta`（Task 7）、`KtorUpdateManifestFetcher`（Task 8）。

> 本任务无新单测——UI 组装类，按 CLAUDE.md「UI 以 ViewModel 单测为主，Compose 快照不强制」。`UpdateViewModel` 已有状态机单测。装配正确性靠编译 + 手动运行（阶段 2 集成时真机验证）。

- [ ] **Step 1: Add i18n keys**

In `Strings.kt` zh block add（en block 对应翻译）：
```
"update_section" to "更新",
"update_source" to "更新源",
"update_check_now" to "立即检查",
"update_checking" to "正在检查…",
"update_no_update" to "已是最新版本",
"update_available" to "发现新版本：%s",
"update_error" to "检查失败：%s",
"update_current_version" to "当前版本：%s",
```
en block：
```
"update_section" to "Updates",
"update_source" to "Update source",
"update_check_now" to "Check now",
"update_checking" to "Checking…",
"update_no_update" to "Up to date",
"update_available" to "New version available: %s",
"update_error" to "Check failed: %s",
"update_current_version" to "Current version: %s",
```

- [ ] **Step 2: Add update section to SettingsScreen**

In `SettingsScreen.kt` 的 Composable 末尾加一个"更新"分区：
```kotlin
// 在 SettingsScreen 的 Column 内，引用外层传入的 updateVm: UpdateViewModel
val updateState by updateVm.state.collectAsState()
val settings by settingsVm.settings.collectAsState()
val sources = remember { UpdateSourceRegistry.all }

SettingsSection(Strings.t("update_section")) {
    Text(Strings.t("update_current_version").format(AppMeta.APP_VERSION))
    Text(Strings.t("update_source"))
    sources.forEach { src ->
        Row(verticalAlignment = androidx.compose.foundation.layout.Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(
                selected = settings.update.sourceId == src.id,
                onClick = { updateVm.selectSource(src.id) },
            )
            Text(src.displayName)
        }
    }
    androidx.compose.material3.Button(onClick = { updateVm.checkForUpdates() }) {
        Text(Strings.t("update_check_now"))
    }
    when (val s = updateState) {
        UpdateState.Checking -> Text(Strings.t("update_checking"))
        UpdateState.NoUpdate -> Text(Strings.t("update_no_update"))
        is UpdateState.Available -> Text(Strings.t("update_available").format(s.version))
        is UpdateState.Error -> Text(Strings.t("update_error").format(s.message))
        UpdateState.Idle -> Unit
    }
}
```
> 若 `SettingsScreen` 现有没有 `SettingsSection` 包装器，用既有分区样式照抄一个块（read 文件确认）。

- [ ] **Step 3: Read SettingsScreen.kt to confirm section style**

Run: Read `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt` — match existing section/grouping idiom; adjust Step 2 snippet to fit（如已有 `Divider`/`Column` 分组，照搬）。

- [ ] **Step 4: Wire in CompositionRoot**

Read `desktop/src/main/kotlin/com/adbgui/desktop/main/CompositionRoot.kt`，在既有 `SettingsViewModel` 构造附近加：
```kotlin
val updateFetcher = KtorUpdateManifestFetcher(io = kotlinx.coroutines.Dispatchers.IO)
val updateChecker = UpdateChecker(updateFetcher, AppMeta.APP_VERSION, fileLogger)
val updateViewModel = UpdateViewModel(updateChecker, settingsStore, appScope)
```
并把 `updateViewModel` 传给 `SettingsScreen` 的调用处。

- [ ] **Step 5: Compile + run app**

Run: `./gradlew :desktop:compileKotlin -q` → BUILD SUCCESSFUL.
Run: `./gradlew :desktop:run` → 应用启动，设置页有"更新"分区，点"立即检查"在 fake/真实源下状态变化（真实源需要联网；本阶段手动验证 UI 行为即可）。

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt desktop/src/main/kotlin/com/adbgui/desktop/main/CompositionRoot.kt
git commit -m "feat(desktop): wire update check into settings UI"
```

---

## 自检结果

**Spec 覆盖**：§3 源模型 → Task 4；§4 manifest → Task 2；§5 架构落点（`:core` fetcher interface + `:desktop` Ktor impl）→ Task 5/8（细化了 spec，HTTP 走接口隔离，`:core` 不引 Ktor）；§6 状态机（Idle/Checking/NoUpdate/Available/Error）→ Task 9；§9 测试（parser fixture、版本比较、checker mock）→ Task 2/3/5；§10 持久化（UpdateSettings）→ Task 6；§11 阶段 1 范围 → 全部 10 任务。阶段 2（下载/安装/MsiUpgrader/便携版打开下载页）与阶段 3（启动静默检查、折叠偏好）留待后续计划，本计划不含。

**Placeholder 扫描**：Task 4 的 `OWNER/ADBGUI` 与 `ghproxy.com` 为发布前必填的结构占位，已在代码注释与 plan 中显式标注，非实现占位；无 TBD/TODO。

**类型一致性**：`UpdateVersion.isGreaterThan` / `UpdateManifestParser.parse` / `UpdateVersionComparer.isNewer(remote,current)` / `UpdateSourceRegistry.byId/default/all` / `UpdateManifestFetcher.fetch` / `UpdateChecker.check(source)` / `UpdateCheckResult{NoUpdate,UpdateAvailable(manifest),Error(message,raw,cause)}` / `UpdateState{Idle,Checking,NoUpdate,Available(version,notes,url),Error(message)}` / `UpdateViewModel.checkForUpdates()/selectSource(id)/state` —— 跨任务签名一致。

## 执行交接

计划已存 `docs/superpowers/plans/2026-09-03-auto-update-stage1.md`。两种执行方式：

1. **Subagent-Driven（推荐）** — 每任务派一个新 subagent，任务间评审，迭代快。
2. **Inline Execution** — 本会话内按 executing-plans 批量执行，带检查点。

选哪种？
