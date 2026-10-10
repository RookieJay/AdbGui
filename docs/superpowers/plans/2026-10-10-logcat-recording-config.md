# Logcat 录制优化（可配置目录 + 总量上限 + 按日合并）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 录制目录可配置（热切换 + 自动迁移）、auto 总量上限清理、auto 文件按日合并、auto/manual 子目录布局、设置页录制管理 UI。

**Architecture:** `Settings` 新增 2 字段；`LogcatRecorder` 支持 append；`LogcatRecordingManager` 的目录从构造常量变为由 settings 推导的 StateFlow（迁移时停止全部录制、搬文件、重启 auto）；磁盘维护（归位/清理/上限/迁移文件操作）抽成独立的 `LogcatRecordingMaintenance` 纯文件操作类（不依赖 adb/coroutine 基建，可独立单测）。

**Tech Stack:** Kotlin / KMP（`:core` 纯 Kotlin + `:desktop` Compose）、kotlinx.coroutines + kotlinx.serialization、kotlin.test。

**Spec:** `docs/superpowers/specs/2026-10-10-logcat-recording-config-design.md`（本计划的依据，执行者需同时读它；基线 spec `2026-09-30-logcat-recording-design.md` 中未被修订的章节仍有效）。

## Global Constraints

- **架构红线**（CLAUDE.md）：`:core` 不 import Compose/AWT/Swing；UI 不碰 adb/CommandRunner；`:core` I/O 一律注入 `io: CoroutineDispatcher`，测试传 `Dispatchers.Unconfined`。
- **`:core` 一律 TDD**：每个行为先写失败测试 → 跑失败 → 最小实现 → 跑通过 → 提交。
- 测试命令用 Gradle wrapper：`./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecorderTest"`（Windows Git Bash 下同样用 `./gradlew`）。
- Conventional Commits：`feat(core):` / `feat(desktop):`。
- 跨线程可变状态 `@Volatile` 或 StateFlow（技术债规范 #3）；不留死代码（#2）：被替换的声明同步删除。
- 文件 ~300 行警线：manager 已 286 行，本计划把磁盘维护逻辑抽到新文件 `LogcatRecordingMaintenance.kt`，避免单文件膨胀。
- 每个任务结束跑全量 `./gradlew :core:test`（或任务涉及的模块），全绿才提交。

---

### Task 1: Settings 新字段（目录 + 总量上限）

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt`（`Settings` data class，`logcatAutoRecord` 字段后插入）
- Test: `core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `Settings.logcatRecordingsDir: String?`（null = 默认目录）、`Settings.logcatRecordingMaxTotalMb: Int`（默认 500）。Task 4/5/6 依赖这两个确切名字。

- [ ] **Step 1: 写失败测试**（追加到 `SettingsStoreTest.kt`，复用文件内已有的 `runTest` / `Files.createTempDirectory` 风格）

```kotlin
@Test
fun logcat_recording_config_default_and_round_trip() = runTest {
    val dir = Files.createTempDirectory("logcat-rec-cfg")
    // 旧格式 JSON（无新字段）→ 默认值
    Files.writeString(dir.resolve("settings.json"), """{"logcatRingCap": 50000}""")
    val store = SettingsStore(dir, io = Dispatchers.Unconfined)
    val loaded = store.load()
    assertNull(loaded.logcatRecordingsDir)
    assertEquals(500, loaded.logcatRecordingMaxTotalMb)
    store.save(loaded.copy(logcatRecordingsDir = "D:/logs", logcatRecordingMaxTotalMb = 2000))
    val reloaded = SettingsStore(dir, io = Dispatchers.Unconfined).load()
    assertEquals("D:/logs", reloaded.logcatRecordingsDir)
    assertEquals(2000, reloaded.logcatRecordingMaxTotalMb)
}
```

（`Dispatchers` 已在其它测试 import；若编译器报缺 import，加 `kotlinx.coroutines.Dispatchers`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.settings.SettingsStoreTest"`
Expected: FAIL，`unresolved reference: logcatRecordingsDir`

- [ ] **Step 3: 最小实现**（`Settings.kt` 内 `Settings`，插在 `logcatAutoRecord` 字段之后）

```kotlin
/** 录制文件存放目录（null = 默认 <配置目录>/logcat-recordings；spec 2026-10-10 D2）。 */
val logcatRecordingsDir: String? = null,
/** auto 录制总占用上限（MB）。超出删最旧 auto 文件；manual 永不自动删（spec 2026-10-10 D4）。 */
val logcatRecordingMaxTotalMb: Int = 500,
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test --tests "com.adbgui.core.settings.SettingsStoreTest"`
Expected: PASS（含全部既有用例——`ignoreUnknownKeys` + 默认值保证旧 settings.json 兼容）

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt
git commit -m "feat(core): logcat recording settings - configurable dir + total size cap"
```

---

### Task 2: LogcatRecorder append 模式

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecorder.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatRecorderTest.kt`

**Interfaces:**
- Produces: `LogcatRecorder` 构造新增 `append: Boolean = false`（放在 `rotationBytes` 之后）。append=true 时：保留已有内容、起录写 `--------- adbgui session start <ts> serial=<serial>` 标记行、`RecordState.bytesWritten` 初值 = 现有文件大小（轮转阈值按文件总大小）。Task 3 的 manager 依赖此参数。
- Consumes: 无（Task 1 与本任务无依赖）。

- [ ] **Step 1: 写失败测试**（追加两个用例；先给测试文件里的 `recorder(...)` 辅助函数加 `append: Boolean = false` 参数并透传）

`recorder` 辅助函数改为：

```kotlin
private fun recorder(
    runner: FakeAdbProcessRunner, scope: CoroutineScope, file: Path,
    rotationBytes: Long = LogcatRecorder.ROTATION_BYTES,
    append: Boolean = false,
): LogcatRecorder {
    val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
    return LogcatRecorder(cmd, NoopLogger, scope, io = Dispatchers.Unconfined, file = file,
        rotationBytes = rotationBytes, append = append)
}
```

新用例：

```kotlin
@Test fun append_mode_preserves_content_and_writes_session_start_marker() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf(line1))
    val dir = Files.createTempDirectory("adbgui-rec")
    val f = dir.resolve("r.log")
    f.writeText("old line\n")
    val r = recorder(runner, this, f, append = true)
    r.start("abc")
    advanceUntilIdle()
    r.stop(null)
    val text = Files.readString(f)
    assertTrue(text.startsWith("old line\n"), "append 模式必须保留已有内容，got: ${text.take(20)}")
    assertTrue(text.contains("--------- adbgui session start"))
    assertTrue(text.contains("serial=abc"))
    assertTrue(text.endsWith(line1 + "\n"))
}

@Test fun append_mode_rotation_threshold_counts_existing_file_size() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLines(listOf(line1))
    val dir = Files.createTempDirectory("adbgui-rec")
    val f = dir.resolve("r.log")
    f.writeText("x")   // 存量 1 字节
    // 阈值 = 存量(1) + 单行字节数：只有把存量计入 bytesWritten 才会触发轮转
    val lineBytes = line1.toByteArray(Charsets.UTF_8).size + 1L
    val r = recorder(runner, this, f, rotationBytes = 1L + lineBytes, append = true)
    r.start("abc")
    advanceUntilIdle()
    assertTrue(r.state.value.rotated, "存量 + 新行应达到轮转阈值（按文件总大小轮转）")
    val text = Files.readString(f)
    assertTrue(text.startsWith("x"))
    assertTrue(text.contains(line1))
}
```

（`kotlin.io.path.writeText` 若未 import 则补上。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecorderTest"`
Expected: FAIL，`unresolved reference: append`（构造无此参数）

- [ ] **Step 3: 最小实现**

`LogcatRecorder` 构造参数列表追加：

```kotlin
    private val rotationBytes: Long = ROTATION_BYTES,
    private val append: Boolean = false,
```

`start()` 内的文件打开段改为（替换原 `withContext(io) { ... }` 块；`withContext` 返回初值）：

```kotlin
val initialBytes = withContext(io) {
    Files.createDirectories(file.parent)
    if (append) {
        val existing = runCatching { Files.size(file) }.getOrDefault(0L)
        writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        val ts = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        )
        writer?.write("--------- adbgui session start $ts serial=$serial\n")
        existing
    } else {
        writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        0L
    }
}
_state.value = RecordState(status = RecordStatus.RECORDING, bytesWritten = initialBytes)
```

其余不动（`runLoop` 的轮转判断读 `_state.value.bytesWritten`，追加计数天然生效）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecorderTest"`
Expected: PASS（全部既有用例不受影响——默认 append=false 行为不变）

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/LogcatRecorder.kt core/src/test/kotlin/com/adbgui/core/device/LogcatRecorderTest.kt
git commit -m "feat(core): logcat recorder append mode for daily file merge"
```

---

### Task 3: Manager 子目录布局 + 按日合并命名

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `LogcatRecorder(append = ...)`。
- Produces: 布局 `<dir>/auto/<label>_<yyyyMMdd>.log`（轮转 `<label>_<yyyyMMdd>_<n>.log`）、`<dir>/manual/<label>_<stamp>.log`；`startAutoLocked(serial, forceNewFile: Boolean = false)`。Task 4/5 依赖此布局。
- 注意：本任务 `recordingsDir` 仍是构造常量（Task 5 才动态化），但所有读写点统一走新的 `autoDir()/manualDir()` 辅助函数，Task 5 只改这两个函数的实现。

- [ ] **Step 1: 写失败测试**

追加 3 个新用例到 `LogcatRecordingManagerTest.kt`：

```kotlin
private fun today(): String =
    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))

@Test fun auto_same_day_session_appends_to_existing_file() = runTest {
    val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
    val dir = Files.createTempDirectory("adbgui-rec")
    val autoDir = Files.createDirectories(dir.resolve("auto"))
    val existing = autoDir.resolve("abc_${today()}.log"); existing.writeText("morning log\n")
    val tracker = FakeTracker()
    val m = manager(runner, this, dir, tracker)
    tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
    advanceUntilIdle()
    tracker.devices.value = emptyList()   // 下线 → flush 收尾
    advanceUntilIdle()
    val text = Files.readString(existing)
    assertTrue(text.startsWith("morning log\n"), "当天已有文件必须追加不截断")
    assertTrue(text.contains("--------- adbgui session start"))
    assertTrue(text.contains(line1))
    m.stop()
    advanceUntilIdle()
}

@Test fun auto_rotation_opens_fresh_suffixed_file_not_append() = runTest {
    val runner = FakeAdbProcessRunner()
    runner.setStreamLinesOnce(listOf(line1, line1))
    val dir = Files.createTempDirectory("adbgui-rec")
    val tracker = FakeTracker()
    val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
    val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
    val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir,
        io = Dispatchers.Unconfined, rotationBytes = line1.length + 1L)
    tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
    advanceUntilIdle()
    // recorder1 写 1 行即轮转 → 续开 abc_<day>_1.log（不追加当天主文件）
    val names = dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }!!.map { it.name }.sorted()
    assertEquals(listOf("abc_${today()}.log", "abc_${today()}_1.log"), names)
    m.stop()
    advanceUntilIdle()
}

@Test fun manual_files_are_written_to_manual_subdir() = runTest {
    val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
    val dir = Files.createTempDirectory("adbgui-rec")
    val tracker = FakeTracker()
    val m = manager(runner, this, dir, tracker)
    tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
    m.startManual("abc")
    advanceUntilIdle()
    val rec = m.manualState.value
    assertTrue(rec is ManualRecordState.Recording)
    assertEquals(dir.resolve("manual"), rec.file.parent)
    m.stopManual("user")
    m.stop()
    advanceUntilIdle()
}
```

- [ ] **Step 2: 更新既有用例的目录断言**（布局变化，同一提交内一起改）

`LogcatRecordingManagerTest.kt` 中：

1. `auto_record_starts_and_stops_with_device_online_state`：`val auto = dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }` → `val auto = dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }`
2. `auto_filename_uses_brand_model_androidversion`：`dir.toFile().listFiles { f -> f.name.startsWith("auto_TCL_618D_Android6.0_") }` → `dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("TCL_618D_Android6.0_") }`
3. `auto_record_disabled_setting_starts_nothing`：两处 `dir.toFile().listFiles { f -> f.name.startsWith("auto_...") }` → `dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }?.size ?: 0`（注意 `listFiles` 对不存在的目录返回 null，必须 `?: 0` 兜底）
4. `rotated_auto_recorder_is_replaced_with_a_new_file`：`dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }` → `dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }`
5. `restartRecordersWithMissingFiles_restarts_auto_session`：before/after 两处同上替换为 `dir.resolve("auto")` 子目录 + `"abc_"` 前缀
6. `latestRecordingFile_returns_most_recent_log_ignoring_other_files`：`dir.resolve("a.log")` / `dir.resolve("b.log")` → `dir.resolve("manual").resolve("a.log")`（先 `Files.createDirectories(dir.resolve("manual"))`）等
7. `startup_cleanup_deletes_old_auto_but_keeps_manual`：`oldAuto` 改到 `dir.resolve("auto")` 下且不带 `auto_` 前缀（如 `auto/old.log`）；`oldManual` 改到 `dir.resolve("manual")` 下（先建两个子目录）。断言不变（`!Files.exists(oldAuto)`、`Files.exists(oldManual)`）。

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecordingManagerTest"`
Expected: FAIL（新用例找不到 `auto/` 子目录文件）

- [ ] **Step 4: 最小实现**（`LogcatRecordingManager.kt`）

companion/类内新增辅助（`stamp()` 旁）：

```kotlin
private fun autoDir(): Path = recordingsDir.resolve("auto")
private fun manualDir(): Path = recordingsDir.resolve("manual")
```

`startManualLocked` 文件行改为：

```kotlin
val file = uniqueFile(manualDir().resolve("${label}_${stamp()}.log"))
```

`startAutoLocked` 签名与文件行改为：

```kotlin
internal suspend fun startAutoLocked(serial: String, forceNewFile: Boolean = false) {
    val label = deviceLabel(serial)
    val file = if (forceNewFile) nextRotationFile(label)
               else autoDir().resolve("${label}_${today()}.log")
    val append = !forceNewFile && Files.exists(file)
    val rec = LogcatRecorder(commands, logger, scope, io, file, rotationBytes, append)
    ...
```

（`today()` 是新增私有函数，见 Step 1 测试里的同名实现，放 companion 或类内均可——放 companion，`stamp()` 旁。`nextRotationFile` 新增：）

```kotlin
/** 轮转/watchdog 重启用：取目录里最小未用的序号开新文件（spec D3，不追加）。 */
private fun nextRotationFile(label: String): Path {
    var n = 1
    while (Files.exists(autoDir().resolve("${label}_${today()}_$n.log"))) n++
    return autoDir().resolve("${label}_${today()}_$n.log")
}
```

auto collector 里轮转续开分支：`startAutoLocked(serial)` → `startAutoLocked(serial, forceNewFile = true)`。

`restartRecordersWithMissingFiles` 的 auto 分支：`stopAutoLocked(serial); startAutoLocked(serial)` → `startAutoLocked(serial, forceNewFile = true)`（文件被删了，换新文件，防止"重启却追加回当天主文件"）。

`latestRecordingFile()` 改为扫两个子目录：

```kotlin
suspend fun latestRecordingFile(): Path? = withContext(io) {
    val files = listOf(autoDir(), manualDir()).flatMap { d ->
        d.toFile().listFiles { f -> f.isFile && f.name.endsWith(".log") }?.toList() ?: emptyList()
    }
    files.maxByOrNull { it.lastModified() }?.toPath()
}
```

`cleanupOldAutoFiles()` 扫根（旧布局）+ `auto/`（新布局）：

```kotlin
internal fun cleanupOldAutoFiles() {
    val cutoff = clock() - RETENTION_MS
    val rootAuto = recordingsDir.toFile()
        .listFiles { f -> f.isFile && f.name.startsWith("auto_") && f.name.endsWith(".log") }?.toList() ?: emptyList()
    val subAuto = autoDir().toFile().listFiles { f -> f.isFile && f.name.endsWith(".log") }?.toList() ?: emptyList()
    (rootAuto + subAuto).filter { it.lastModified() < cutoff }
        .forEach { runCatching { it.delete() } }
}
```

（Task 4 会把清理整体重写进 `LogcatRecordingMaintenance`，此处先保证 7 天语义在新布局下不回归。）

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :core:test`
Expected: PASS（core 全量；`LogcatRecorderTest` 不受影响）

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt
git commit -m "feat(core): auto/manual subdirs + daily-merge recording file naming"
```

---

### Task 4: LogcatRecordingMaintenance（归位 + 0 字节 + 总量上限）

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingMaintenance.kt`
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt`（init 清理接线 + 开新文件后 enforce；删除 `cleanupOldAutoFiles` 与 `RETENTION_MS`）
- Test: Create `core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingMaintenanceTest.kt`；Modify `LogcatRecordingManagerTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `autoDir()/manualDir()` 布局；Task 1 的 `logcatRecordingMaxTotalMb`。
- Produces: `LogcatRecordingMaintenance(logger: Logger, clock: () -> Long)`，方法：`cleanupOnStartup(dir: Path, active: Set<Path>)`、`reorganizeLegacyFiles(dir: Path)`、`enforceSizeCap(dir: Path, capBytes: Long, active: Set<Path>)`、`totalBytes(dir: Path): Long`。manager 持一个实例委托调用。Task 5 增补 `migrateFiles(oldDir, newDir)`。

- [ ] **Step 1: 写失败测试**（新文件 `LogcatRecordingMaintenanceTest.kt`——纯文件操作，无 adb/coroutine 基建）

```kotlin
package com.adbgui.core.device

import com.adbgui.core.log.NoopLogger
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogcatRecordingMaintenanceTest {
    private val now = System.currentTimeMillis()
    private val m = LogcatRecordingMaintenance(NoopLogger, clock = { now })

    private fun dir(): java.nio.file.Path = Files.createTempDirectory("adbgui-mnt").also {
        Files.createDirectories(it.resolve("auto"))
        Files.createDirectories(it.resolve("manual"))
    }

    @Test fun reorganize_moves_legacy_root_files_to_subdirs() {
        val d = dir()
        d.resolve("auto_legacy.log").writeText("x")
        d.resolve("manual_legacy.log").writeText("x")
        m.reorganizeLegacyFiles(d)
        assertTrue(Files.exists(d.resolve("auto").resolve("auto_legacy.log")))
        assertTrue(Files.exists(d.resolve("manual").resolve("manual_legacy.log")))
        assertFalse(Files.exists(d.resolve("auto_legacy.log")))
        assertFalse(Files.exists(d.resolve("manual_legacy.log")))
    }

    @Test fun cleanup_deletes_old_auto_and_stale_zero_byte_but_keeps_manual_and_fresh() {
        val d = dir()
        val oldAuto = d.resolve("auto").resolve("old.log"); oldAuto.writeText("x")
        val staleEmpty = d.resolve("auto").resolve("stale_empty.log")   // 0 字节 + 2 天前
        val freshEmpty = d.resolve("auto").resolve("fresh_empty.log")   // 0 字节但新（正在录的空流）
        val oldManual = d.resolve("manual").resolve("m.log"); oldManual.writeText("x")
        oldAuto.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        staleEmpty.toFile().setLastModified(now - 2L * 24 * 60 * 60 * 1000)
        oldManual.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        m.cleanupOnStartup(d, capBytes = Long.MAX_VALUE, active = emptySet())
        assertFalse(Files.exists(oldAuto))      // 7 天 → 删
        assertFalse(Files.exists(staleEmpty))   // 0 字节超 1 天 → 删
        assertTrue(Files.exists(freshEmpty))    // 0 字节但新鲜 → 留
        assertTrue(Files.exists(oldManual))     // manual 永不自动删
    }

    @Test fun enforce_size_cap_deletes_oldest_first_skipping_active_and_manual() {
        val d = dir()
        fun file(name: String, ageMin: Long) {
            val f = d.resolve("auto").resolve(name)
            f.writeText("x")
            f.toFile().setLastModified(now - ageMin * 60_000L)
        }
        file("old.log", 100); file("mid.log", 50); file("new.log", 10); file("active.log", 5)
        d.resolve("manual").resolve("m.log").writeText("x")
        // 4 个 auto 各 1 字节，cap=2：删 old+mid（活动文件 active.log 保护）
        m.enforceSizeCap(d, capBytes = 2, active = setOf(d.resolve("auto").resolve("active.log")))
        assertFalse(Files.exists(d.resolve("auto").resolve("old.log")))
        assertFalse(Files.exists(d.resolve("auto").resolve("mid.log")))
        assertTrue(Files.exists(d.resolve("auto").resolve("new.log")))
        assertTrue(Files.exists(d.resolve("auto").resolve("active.log")))
        assertTrue(Files.exists(d.resolve("manual").resolve("m.log")))
    }

    @Test fun enforce_size_cap_noop_when_already_under_cap() {
        val d = dir()
        d.resolve("auto").resolve("only.log").writeText("x")
        m.enforceSizeCap(d, capBytes = 1024, active = emptySet())
        assertTrue(Files.exists(d.resolve("auto").resolve("only.log")))
    }

    @Test fun total_bytes_sums_log_files_in_both_subdirs_ignoring_others() {
        val d = dir()
        d.resolve("auto").resolve("a.log").writeText("x")
        d.resolve("manual").resolve("b.log").writeText("xy")
        d.resolve("auto").resolve("notes.txt").writeText("x")
        assertEquals(3L, m.totalBytes(d))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecordingMaintenanceTest"`
Expected: FAIL，`unresolved reference: LogcatRecordingMaintenance`

- [ ] **Step 3: 最小实现**（新文件）

```kotlin
package com.adbgui.core.device

import com.adbgui.core.log.Logger
import java.nio.file.Files
import java.nio.file.Path

/** 录制目录的磁盘维护（spec 2026-10-10 D4/D5）：归位、过期/0 字节清理、总量上限、占用统计。
 *  纯文件操作、不依赖 adb 与协程基建，manager 持实例委托调用；所有方法同步阻塞，调用方负责跑在 io 上。 */
internal class LogcatRecordingMaintenance(
    private val logger: Logger,
    private val clock: () -> Long,
) {
    /** 启动清理：归位 → 7 天 → 0 字节陈旧 → 总量上限（spec D4/D5）。cap 由调用方（manager，读 settings）传入。 */
    fun cleanupOnStartup(dir: Path, capBytes: Long, active: Set<Path>) {
        reorganizeLegacyFiles(dir)
        val autoFiles = autoLogFiles(dir) ?: return
        val retentionCutoff = clock() - RETENTION_MS
        val zeroByteCutoff = clock() - ZERO_BYTE_MS
        autoFiles.filter { it.toPath() !in active }.forEach { f ->
            runCatching {
                when {
                    f.lastModified() < retentionCutoff -> f.delete()
                    f.length() == 0L && f.lastModified() < zeroByteCutoff -> f.delete()
                }
            }.onFailure { logger.warn("recording cleanup failed for ${f.name}: ${it.message}") }
        }
        enforceSizeCap(dir, capBytes, active)
    }

    /** 旧布局（文件在录制目录根）一次性归位：auto_*.log → auto/，其余 .log → manual/（spec D5）。
     *  文件名保留原样（含 auto_ 前缀）不重命名；挪不动的 WARN 跳过，下次启动再试。 */
    fun reorganizeLegacyFiles(dir: Path) {
        val root = dir.toFile()
        if (!root.isDirectory) return
        root.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { f ->
            runCatching {
                val sub = if (f.name.startsWith("auto_")) "auto" else "manual"
                val target = dir.resolve(sub)
                Files.createDirectories(target)
                Files.move(f.toPath(), target.resolve(f.name))
            }.onFailure { logger.warn("failed to reorganize ${f.name}: ${it.message}") }
        }
    }

    /** 总量上限（spec D4）：auto/ 从旧到新删到 cap 以下；活动文件与 manual 不动；删不到只 WARN 不抛。 */
    fun enforceSizeCap(dir: Path, capBytes: Long, active: Set<Path>) {
        val files = autoLogFiles(dir) ?: return
        var total = files.sumOf { it.length() }
        if (total <= capBytes) return
        files.filter { it.toPath() !in active }
            .sortedBy { it.lastModified() }
            .forEach { f ->
                if (total <= capBytes) return@forEach
                if (runCatching { f.delete() }.getOrDefault(false)) total -= f.length()
            }
        if (total > capBytes) {
            logger.warn("recording size cap cannot be met (active files held); total=$total cap=$capBytes")
        }
    }

    /** auto/ + manual/ 下 .log 文件总字节数（设置页占用展示）。 */
    fun totalBytes(dir: Path): Long =
        listOf("auto", "manual").sumOf { sub ->
            dir.resolve(sub).toFile()
                .listFiles { f -> f.isFile && f.name.endsWith(".log") }?.sumOf { it.length() } ?: 0L
        }

    private fun autoLogFiles(dir: Path) =
        dir.resolve("auto").toFile().listFiles { f -> f.isFile && f.name.endsWith(".log") }

    companion object {
        const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val ZERO_BYTE_MS: Long = 24L * 60 * 60 * 1000
    }
}
```

（cap 值由 manager 读 settings 后传入——maintenance 不依赖 `SettingsStore`，保持纯文件操作。）

- [ ] **Step 4: manager 接线**（`LogcatRecordingManager.kt`）

构造参数后新增字段（复用注入的 `clock`）：

```kotlin
private val maintenance = LogcatRecordingMaintenance(logger, clock)
private fun capBytes(): Long = settings.state.logcatRecordingMaxTotalMb * 1024L * 1024L
private suspend fun activeFilesSnapshot(): Set<Path> =
    mutex.withLock { autoFiles.values.toSet() + listOfNotNull(manualFile) }
```

`init` 的清理 launch 改为：

```kotlin
scope.launch {
    val active = activeFilesSnapshot()
    withContext(io) { runCatching { maintenance.cleanupOnStartup(recordingsDir, capBytes(), active) } }
        .onFailure { logger.warn("recording cleanup failed: ${it.message}") }
}
```

`startAutoLocked` 末尾（`rec.start(serial)` 之后）追加（异步，不阻塞持锁调用方）：

```kotlin
scope.launch {
    val active = activeFilesSnapshot()
    withContext(io) { runCatching { maintenance.enforceSizeCap(recordingsDir, capBytes(), active) } }
}
```

删除 `cleanupOldAutoFiles()` 方法与 companion 里的 `RETENTION_MS`（被 maintenance 替代；技术债规范 #2 不留死代码）。新增对外委托：

```kotlin
/** auto/ + manual/ 总占用（设置页展示）。目录扫描在 [io] 上执行。 */
suspend fun totalRecordingBytes(): Long = withContext(io) { maintenance.totalBytes(recordingsDir) }
```

- [ ] **Step 5: 跑测试确认通过**（含 manager 侧启动清理既有用例——`startup_cleanup_deletes_old_auto_but_keeps_manual` 在 Task 3 已改到新布局，本步验证仍绿）

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingMaintenance.kt core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingMaintenanceTest.kt core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt
git commit -m "feat(core): recording maintenance - legacy reorganize, zero-byte + size-cap cleanup"
```

---

### Task 5: 录制目录动态化 + 热切换迁移 + 上限变更触发

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt`
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingMaintenance.kt`（增补 `migrateFiles`）
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt`

**Interfaces:**
- Consumes: Task 1 `logcatRecordingsDir`、Task 4 `maintenance`。
- Produces: `LogcatRecordingManager.recordingsDir: Path`（从构造 `val` 改为 computed property，取值 = 当前有效目录——公开 API 形状不变，`LogcatViewModel` 的 `get() = recording.recordingsDir` 无需改动）；`maintenance.migrateFiles(oldDir: Path, newDir: Path)`。Task 6 依赖 `totalRecordingBytes()`（Task 4 已产）与目录推导行为。

- [ ] **Step 1: 写失败测试**（`LogcatRecordingManagerTest.kt` 追加）

```kotlin
@Test fun changing_dir_migrates_files_and_restarts_auto_in_new_dir() = runTest {
    val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
    val dir1 = Files.createTempDirectory("adbgui-rec1")
    val dir2 = Files.createTempDirectory("adbgui-rec2")
    val tracker = FakeTracker()
    val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
    val settings = SettingsStore(dir1, io = Dispatchers.Unconfined)
    val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir1, io = Dispatchers.Unconfined)
    tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
    advanceUntilIdle()
    assertTrue(m.autoStates.value.containsKey("abc"))
    val oldFile = dir1.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }!!.single().toPath()
    settings.update { it.copy(logcatRecordingsDir = dir2.toString()) }
    advanceUntilIdle()
    // 迁移：文件到新目录；auto 在新目录重启（当天文件被续写，仍只有这一个 abc_ 文件）
    assertTrue(m.autoStates.value.containsKey("abc"))
    assertTrue(Files.exists(dir2.resolve("auto").resolve(oldFile.fileName)))
    assertTrue(!Files.exists(oldFile))
    assertEquals(dir2, m.recordingsDir)
    assertEquals(1, dir2.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }!!.size)
    m.stop()
    advanceUntilIdle()
}

@Test fun changing_dir_stops_manual_with_reason_and_moves_file() = runTest {
    val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
    val dir1 = Files.createTempDirectory("adbgui-rec1")
    val dir2 = Files.createTempDirectory("adbgui-rec2")
    val tracker = FakeTracker()
    val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
    val settings = SettingsStore(dir1, io = Dispatchers.Unconfined)   // 默认目录 = dir1
    val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir1, io = Dispatchers.Unconfined)
    tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
    m.startManual("abc")
    advanceUntilIdle()
    val manualFile = (m.manualState.value as ManualRecordState.Recording).file
    settings.update { it.copy(logcatRecordingsDir = dir2.toString()) }
    advanceUntilIdle()
    val st = m.manualState.value
    assertTrue(st is ManualRecordState.Stopped)
    assertEquals("recording_dir_changed", st.reason)
    assertTrue(Files.exists(dir2.resolve("manual").resolve(manualFile.fileName)))
    m.stop()
    advanceUntilIdle()
}

@Test fun migration_skips_conflicting_file_and_continues() = runTest {
    val runner = FakeAdbProcessRunner()
    val dir1 = Files.createTempDirectory("adbgui-rec1")
    val dir2 = Files.createTempDirectory("adbgui-rec2")
    val tracker = FakeTracker()
    val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
    val settings = SettingsStore(dir1, io = Dispatchers.Unconfined)
    settings.update { it.copy(logcatAutoRecord = false) }
    val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir1, io = Dispatchers.Unconfined)
    advanceUntilIdle()
    // dir1/auto 放两个文件；dir2/auto 预置同名冲突文件 → 该文件 move 失败跳过，另一个照常迁移
    Files.createDirectories(dir1.resolve("auto")); Files.createDirectories(dir2.resolve("auto"))
    val conflict = dir1.resolve("auto").resolve("x.log"); conflict.writeText("1")
    val plain = dir1.resolve("auto").resolve("y.log"); plain.writeText("2")
    dir2.resolve("auto").resolve("x.log").writeText("conflict")
    settings.update { it.copy(logcatRecordingsDir = dir2.toString()) }
    advanceUntilIdle()
    assertTrue(Files.exists(dir2.resolve("auto").resolve("y.log")), "无冲突文件照常迁移")
    assertTrue(!Files.exists(plain), "源文件已移走")
    assertTrue(Files.exists(conflict), "冲突文件留在原地")
    assertTrue(Files.readString(dir2.resolve("auto").resolve("x.log")) == "conflict", "目标既有文件不被覆盖")
    m.stop()
    advanceUntilIdle()
}
```

（`assertEquals` 比较字符串用 `assertTrue(Files.readString(...) == "conflict")` 形式即可。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecordingManagerTest"`
Expected: FAIL（改 settings 后文件不迁移，`Files.exists(oldFile)` 断言失败/目录断言失败）

- [ ] **Step 3: 最小实现**

`LogcatRecordingMaintenance.kt` 增补：

```kotlin
/** 目录切换迁移（spec D2）：旧目录根 .log 与 auto/、manual/ 子目录文件搬到新目录对应位置；
 *  逐文件失败跳过 + WARN；旧目录空了删空目录。调用方先停录制、后调用、再重启。 */
fun migrateFiles(oldDir: Path, newDir: Path) {
    if (!oldDir.toFile().isDirectory) return
    runCatching {
        Files.createDirectories(newDir.resolve("auto"))
        Files.createDirectories(newDir.resolve("manual"))
        // 旧布局根文件（从未归位的）
        oldDir.toFile().listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { f ->
            val sub = if (f.name.startsWith("auto_")) "auto" else "manual"
            moveOrWarn(f.toPath(), newDir.resolve(sub).resolve(f.name))
        }
        listOf("auto", "manual").forEach { sub ->
            oldDir.resolve(sub).toFile().listFiles { f -> f.isFile }?.forEach { f ->
                moveOrWarn(f.toPath(), newDir.resolve(sub).resolve(f.name))
            }
        }
        if (oldDir.toFile().listFiles()?.isEmpty() == true) {
            runCatching { Files.deleteIfExists(oldDir) }
        }
    }.onFailure { logger.warn("recording migration failed: ${it.message}") }
}

private fun moveOrWarn(src: Path, dst: Path) {
    runCatching { Files.move(src, dst) }
        .onFailure { logger.warn("migration skipped ${src.fileName}: ${it.message}") }
}
```

`LogcatRecordingManager.kt`：

构造参数 `val recordingsDir: Path` 改为 `private val defaultDir: Path`（位置参数调用方不受影响），并新增：

```kotlin
private val _recordingsDir = MutableStateFlow(effectiveDir(settings.state.value))
val recordingsDir: Path get() = _recordingsDir.value   // 公开 API 形状不变（LogcatViewModel 取值即最新）

private fun effectiveDir(s: com.adbgui.core.settings.Settings): Path =
    s.logcatRecordingsDir?.let { raw ->
        runCatching { Path.of(raw) }
            .onFailure { logger.warn("invalid recordings dir '$raw'; using default") }
            .getOrNull()
    } ?: defaultDir

private fun autoDir(): Path = _recordingsDir.value.resolve("auto")   // Task 3 的辅助改为读动态目录
private fun manualDir(): Path = _recordingsDir.value.resolve("manual")
```

（Task 4 接线处 `recordingsDir` 全部替换为 `_recordingsDir.value`：`maintenance.cleanupOnStartup(_recordingsDir.value, ...)`、`enforceSizeCap(_recordingsDir.value, ...)`、`totalBytes(_recordingsDir.value)`。）

`observeDevices()` 改为（在现有 manual/auto 增删逻辑之前插入目录/上限维度）：

```kotlin
private suspend fun observeDevices() {
    var lastDir: String? = settings.state.value.logcatRecordingsDir
    var lastCap: Int = settings.state.value.logcatRecordingMaxTotalMb
    combine(settings.state, tracker.devices) { s, devs -> s to devs }.collect { (s, devs) ->
        val online = devs.filter { it.status == DeviceStatus.ONLINE }.map { it.serial }.toSet()
        // 目录热切换（spec D2）：停全部 → 迁移 → 更新有效目录 → 走下方常规逻辑重启 auto
        if (s.logcatRecordingsDir != lastDir) {
            lastDir = s.logcatRecordingsDir
            val newDir = effectiveDir(s)
            if (newDir != _recordingsDir.value) {
                if (_manualState.value is ManualRecordState.Recording) stopManual("recording_dir_changed")
                mutex.withLock { autoRecorders.keys.toList().forEach { stopAutoLocked(it) } }
                withContext(io) { runCatching { maintenance.migrateFiles(_recordingsDir.value, newDir) } }
                _recordingsDir.value = newDir
                logger.info("recordings dir changed to $newDir (migrated)")
            }
        }
        // 上限变更 → 立即执行一次（spec D4）
        if (s.logcatRecordingMaxTotalMb != lastCap) {
            lastCap = s.logcatRecordingMaxTotalMb
            val active = activeFilesSnapshot()
            withContext(io) { runCatching { maintenance.enforceSizeCap(_recordingsDir.value, capBytes(), active) } }
        }
        val manual = _manualState.value
        if (manual is ManualRecordState.Recording && manual.serial !in online) {
            stopManual("device_disconnected")
        }
        mutex.withLock {
            autoRecorders.keys.filter { it !in online }.forEach { stopAutoLocked(it) }
            if (s.logcatAutoRecord) online.filter { it !in autoRecorders }.forEach { startAutoLocked(it) }
            else autoRecorders.keys.toList().forEach { stopAutoLocked(it) }
        }
    }
}
```

（原 combine 的 map `{ s.logcatAutoRecord to devs }` 被整体替换；auto 开关判断改读 `s.logcatAutoRecord`。）

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test` 然后 `./gradlew :desktop:test`
Expected: 双 PASS（desktop 的 `LogcatViewModelTest` 用 `rec.recordingsDir`——computed property 形状不变，应仍绿）

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingMaintenance.kt core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt
git commit -m "feat(core): configurable recordings dir with hot-switch migration"
```

---

### Task 6: 设置页 UI + ViewModel + i18n + 接线

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsViewModel.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt`（"Auto logcat recording"节，约 249-258 行处扩展）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`（zh 段 ~227 行、en 段 ~683 行附近各加一组键）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt`（57/84 行：logcatRecording 提前构造，传入 settingsVm）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt:219`（新增 `recording_dir_changed` reason 映射）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: Task 5 的 `totalRecordingBytes()`、`recordingsDir`；Task 1 的 settings 字段。
- Produces: `SettingsViewModel(store, scope, recording: LogcatRecordingManager? = null)`（第三参默认 null，既有调用/测试不破）、`setLogcatRecordingsDir(path: String?)`、`setLogcatRecordingMaxTotalMb(mb: Int)`、`recordingsUsage: StateFlow<Long?>`、`refreshRecordingsUsage()`。

- [ ] **Step 1: 写失败测试**（`SettingsViewModelTest.kt` 追加；参照 `LogcatViewModelTest` 里构造 manager 的模式）

```kotlin
private val adb = com.adbgui.core.domain.AdbBinary("adb", com.adbgui.core.domain.AdbSource.PATH)

private class FakeTracker : com.adbgui.core.device.IDeviceTracker {
    override val devices = kotlinx.coroutines.flow.MutableStateFlow<List<com.adbgui.core.domain.DeviceSnapshot>>(emptyList())
}

@Test fun setLogcatRecordingsDir_persists_and_resets() = runTest {
    val dir = Files.createTempDirectory("rec-dir")
    val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
    val vm = SettingsViewModel(store, this)
    vm.setLogcatRecordingsDir("D:/logs")
    advanceUntilIdle()
    assertEquals("D:/logs", store.state.value.logcatRecordingsDir)
    vm.setLogcatRecordingsDir(null)
    advanceUntilIdle()
    assertEquals(null, store.state.value.logcatRecordingsDir)
}

@Test fun setLogcatRecordingMaxTotalMb_persists() = runTest {
    val dir = Files.createTempDirectory("rec-cap")
    val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
    val vm = SettingsViewModel(store, this)
    vm.setLogcatRecordingMaxTotalMb(2000)
    advanceUntilIdle()
    assertEquals(2000, store.state.value.logcatRecordingMaxTotalMb)
}

@Test fun refreshRecordingsUsage_reports_total_of_auto_and_manual() = runTest {
    val recDir = Files.createTempDirectory("adbgui-rec")
    Files.createDirectories(recDir.resolve("auto")); Files.createDirectories(recDir.resolve("manual"))
    recDir.resolve("auto").resolve("a.log").writeText("x")
    recDir.resolve("manual").resolve("b.log").writeText("xy")
    val runner = com.adbgui.core.adb.FakeAdbProcessRunner()
    val cmd = com.adbgui.core.adb.CommandRunner(
        { adb }, runner, com.adbgui.core.log.NoopLogger, this, com.adbgui.core.adb.CommandRunner.AdbServerStarter {})
    val settings = SettingsStore(recDir, io = kotlinx.coroutines.Dispatchers.Unconfined)
    settings.update { it.copy(logcatAutoRecord = false) }
    val rec = com.adbgui.core.device.LogcatRecordingManager(
        cmd, FakeTracker(), settings, com.adbgui.core.log.NoopLogger, this, recDir,
        io = kotlinx.coroutines.Dispatchers.Unconfined)
    val vm = SettingsViewModel(settings, this, rec)
    vm.refreshRecordingsUsage()
    advanceUntilIdle()
    assertEquals(3L, vm.recordingsUsage.value)
    rec.stop()
    advanceUntilIdle()
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.SettingsViewModelTest"`
Expected: FAIL，`unresolved reference: setLogcatRecordingsDir`

- [ ] **Step 3: 最小实现**

`SettingsViewModel.kt`：

```kotlin
class SettingsViewModel(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val recording: com.adbgui.core.device.LogcatRecordingManager? = null,
) {
    ...既有...
    fun setLogcatRecordingsDir(path: String?) = scope.launch { store.update { it.copy(logcatRecordingsDir = path) } }
    fun setLogcatRecordingMaxTotalMb(mb: Int) = scope.launch { store.update { it.copy(logcatRecordingMaxTotalMb = mb) } }

    private val _recordingsUsage = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val recordingsUsage: kotlinx.coroutines.flow.StateFlow<Long?> = _recordingsUsage
    fun refreshRecordingsUsage() = scope.launch { _recordingsUsage.value = recording?.totalRecordingBytes() }
}
```

`Main.kt`：把 `logcatRecording`（现 84-89 行）挪到 `settingsVm`（57 行）之前，`settingsVm` 改为：

```kotlin
val settingsVm = remember { SettingsViewModel(root.settings, root.scope, logcatRecording) }
```

`Strings.kt` zh 段（`logcat_auto_record_desc` 后）与 en 段对应位置各加：

```kotlin
// zh
"logcat_rec_storage" to "录制存储",
"logcat_rec_dir_change" to "更改…",
"logcat_rec_dir_open" to "打开",
"logcat_rec_dir_reset" to "重置为默认",
"logcat_rec_pick_dir" to "选择录制目录",
"logcat_rec_cap" to "总占用上限（MB）",
"logcat_rec_cap_hint" to "自动录制总占用超过上限时删除最旧的（手动录制不受影响）；文件另保留 7 天",
"logcat_rec_cap_err" to "需为 50–100000",
"logcat_rec_cap_set" to "上限已设为 %d MB",
"logcat_rec_usage" to "已用 %s / 上限 %d MB",
"record_stopped_dir_changed" to "录制目录已更改，录制已停止，文件已迁移",
// en
"logcat_rec_storage" to "Recording storage",
"logcat_rec_dir_change" to "Change…",
"logcat_rec_dir_open" to "Open",
"logcat_rec_dir_reset" to "Reset to default",
"logcat_rec_pick_dir" to "Choose recordings folder",
"logcat_rec_cap" to "Total size cap (MB)",
"logcat_rec_cap_hint" to "Oldest auto files are deleted when total usage exceeds the cap (manual recordings are never deleted); files are also kept for 7 days",
"logcat_rec_cap_err" to "Must be 50–100000",
"logcat_rec_cap_set" to "Cap set to %d MB",
"logcat_rec_usage" to "Used %s / cap %d MB",
"record_stopped_dir_changed" to "Recordings folder changed — recording stopped, file migrated",
```

`SettingsScreen.kt`：在 auto-record `Switch` 块（~257 行）后、`Divider()` 前插入（复用本文件已有的 `OutlinedTextField`/`Button`/`remember` 模式与 `status` 变量）：

```kotlin
// --- Recording storage (dir + cap + usage) ---
Text(Strings.t("logcat_rec_storage"), style = MaterialTheme.typography.subtitle1)
val effectiveDir = settings.logcatRecordingsDir
    ?: configDir.resolve("logcat-recordings").toAbsolutePath().toString()
val usage by vm.recordingsUsage.collectAsState()
LaunchedEffect(settings.logcatRecordingsDir, settings.logcatRecordingMaxTotalMb) {
    vm.refreshRecordingsUsage()
}
Row(verticalAlignment = Alignment.CenterVertically) {
    SelectableText(
        effectiveDir,
        style = MaterialTheme.typography.body2,
        modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    Button(onClick = {
        val picked = com.adbgui.desktop.platform.FileDialogs.pickDirectory(
            Strings.t("logcat_rec_pick_dir"), effectiveDir)
        if (picked != null) vm.setLogcatRecordingsDir(picked)
    }) { Text(Strings.t("logcat_rec_dir_change")) }
    Spacer(Modifier.width(8.dp))
    Button(onClick = {
        val d = File(effectiveDir)
        runCatching { if (!d.exists()) d.mkdirs(); Desktop.getDesktop().open(d) }
            .onFailure { status = Strings.t("status_open_failed").format(it.message) }
    }) { Text(Strings.t("logcat_rec_dir_open")) }
    if (settings.logcatRecordingsDir != null) {
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { vm.setLogcatRecordingsDir(null) }) {
            Text(Strings.t("logcat_rec_dir_reset"))
        }
    }
}
usage?.let {
    Text(
        Strings.t("logcat_rec_usage").format(fmtBytes(it), settings.logcatRecordingMaxTotalMb),
        style = MaterialTheme.typography.caption,
    )
}
var capDraft by remember(settings.logcatRecordingMaxTotalMb) {
    mutableStateOf(settings.logcatRecordingMaxTotalMb.toString())
}
Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
        value = capDraft,
        onValueChange = { capDraft = it.filter { c -> c.isDigit() } },
        label = { Text(Strings.t("logcat_rec_cap")) },
        singleLine = true,
        modifier = Modifier.width(200.dp),
    )
    Spacer(Modifier.width(8.dp))
    Button(onClick = {
        val n = capDraft.trim().toIntOrNull()
        if (n != null && n in 50..100_000) {
            vm.setLogcatRecordingMaxTotalMb(n)
            status = Strings.t("logcat_rec_cap_set").format(n)
        } else {
            status = Strings.t("logcat_rec_cap_err")
        }
    }) { Text(Strings.t("apply")) }
}
Text(Strings.t("logcat_rec_cap_hint"), style = MaterialTheme.typography.caption)
```

文件内（`zipDirectory` 旁）加辅助函数：

```kotlin
private fun fmtBytes(bytes: Long): String =
    if (bytes < 1024L * 1024) "%d KB".format(bytes / 1024)
    else "%d MB".format(bytes / (1024 * 1024))
```

`LogcatScreen.kt:219` 的 reason 分支追加（与 device_disconnected / write_failed 并列）：

```kotlin
else if (mr.reason == "recording_dir_changed") {
    InlineMessageBanner(Strings.t("record_stopped_dir_changed"), MessageKind.Info)
}
```

（`MessageKind` 已在本文件 import；若没有 `Info` 枚举则用现有最接近的非错误级别——先查 `MessageKind` 定义再选。）

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :desktop:test`（core 已在 Task 5 全绿，可再跑一次全量 `./gradlew :core:test :desktop:test`）
Expected: PASS

- [ ] **Step 5: 真机验证（用户执行）**

启动前先杀残留 JVM（TaskStop/gradle kill 不一定带走应用进程）：任务管理器结束 `adbgui`/`MainKt` 相关 java 进程，或 `taskkill //F //IM java.exe` 谨慎使用。然后 `./gradlew :desktop:run`，验证：

1. 设置页"录制存储"显示默认目录与占用；「更改…」选新目录 → 日志页后台录制 chip 点击打开的是新目录、旧文件出现在新目录。
2. 录制中改目录 → 录制停止提示"录制目录已更改"。
3. 设备反复重连 → `auto/` 下同设备当天只有一个文件，重连内容追加（含 session start 标记）。
4. 把上限改成 50（MB）→ 旧文件被清到 50 MB 以下。
5. 旧根目录文件（你现有的 21 个）首次启动被归位到 `auto/`/`manual/`。

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsViewModel.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/SettingsViewModelTest.kt
git commit -m "feat(desktop): settings UI for recordings dir, size cap and usage"
```

---

### Task 7: CHANGELOG + spec 状态收尾

**Files:**
- Modify: `CHANGELOG.md`（新版本节，格式参照既有节）
- Modify: `docs/superpowers/specs/2026-10-10-logcat-recording-config-design.md`（状态行改"已实现"）

- [ ] **Step 1: CHANGELOG 记录**（概述：可配置目录+迁移、总量上限、按日合并、子目录布局、设置页 UI、归位升级；用户视角描述，中文，与既有条目风格一致）

- [ ] **Step 2: Commit**

```bash
git add CHANGELOG.md docs/superpowers/specs/2026-10-10-logcat-recording-config-design.md
git commit -m "docs: changelog for logcat recording config"
```
