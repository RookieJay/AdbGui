# Logcat 录制（自动 + 手动）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 Logcat 录制子系统——独立 adb 进程逐行把原始日志 tee 到文件（无行数/时长上限），支持手动开始/停止与会话内后台自动录制（设置开关），录制文件自动轮转与过期清理。

**Architecture:** `:core` 新增 `LogcatRecorder`（一个 adb logcat 流 → 一个文件，断流退避重连）与 `LogcatRecordingManager`（编排层：手动会话 + 监听 tracker/settings 的自动录制 + 启动清理 + 轮转续开）。`:desktop` 的 `LogcatViewModel`/`LogcatScreen` 只做转发与展示，`Main.kt` 用 `remember{}` 构造 manager 一次。

**Tech Stack:** Kotlin (KMP, :core 纯 Kotlin + :desktop Compose Multiplatform)、kotlinx.coroutines（TestScope/advanceUntilIdle）、kotlin.test、java.nio.file。

**Spec:** `docs/superpowers/specs/2026-09-30-logcat-recording-design.md`（本计划所有决策从 spec 来，执行者需同时读 spec）

## Global Constraints

- `:core` 不依赖 Compose/AWT/Swing（CLAUDE.md 红线 #1）。
- `:core` 所有 I/O 注入 `io: CoroutineDispatcher`，测试传 `Dispatchers.Unconfined`（技术债规范 #1）。
- 跨线程 `var` 必须 `@Volatile` 或 Mutex 保护（技术债规范 #3）。
- `:core` 一律 TDD：先写失败测试 → 验证失败 → 最小实现 → 验证通过 → 提交。
- 测试命令：`./gradlew :core:test --tests "*.<包>.<类>"`（core）、`./gradlew :desktop:test --tests "*.<包>.<类>"`（desktop）。
- Conventional Commits：`feat(core):` / `feat(desktop):` 等。
- 每个任务一个提交（任务内逻辑步骤可再拆）。
- 不留死代码：任何新增类/参数必须被引用（技术债规范 #2）。
- 录制写原始行 + `\n`（不用 `appendLine`，避免 Windows `\r\n` 混入日志文件）。
- 录制文件名 serial 净化：`:` 与 `[\\/*?"<>|]` 替换为 `_`（无线 serial 含 `:`，Windows 非法）。

---

### Task 0: 开分支

**Files:** 无（只 git 操作）

- [ ] **Step 1: 从 master 开新分支**

```bash
git checkout -b feat/logcat-recording master
```

Expected: 切到新分支，工作区干净。

---

### Task 1: `LogcatRecorder`（:core）

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecorder.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatRecorderTest.kt`

**Interfaces:**
- Consumes: `CommandRunner.streamLogcat(serial: String): AdbStream`（既有，`CommandRunner.kt:264`）、`AdbStream`（`AdbProcessRunner.kt:11`）、`Logger`。
- Produces（后续任务依赖，签名精确如下）:

```kotlin
enum class RecordStatus { RECORDING, STOPPED }

data class RecordState(
    val status: RecordStatus = RecordStatus.STOPPED,
    val lineCount: Long = 0,
    val bytesWritten: Long = 0,
    val error: String? = null,
    val rotated: Boolean = false,      // 文件写满 rotationBytes，recorder 已原地收尾
    val stopReason: String? = null,    // "user" / "device_disconnected" / "write_failed" / null
)

class LogcatRecorder(
    commands: CommandRunner, logger: Logger, scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    file: Path, rotationBytes: Long = LogcatRecorder.ROTATION_BYTES,
) {
    val state: StateFlow<RecordState>
    fun start(serial: String)          // 幂等：已在录则忽略
    suspend fun stop(reason: String?)  // cancel job + kill stream + flush/close；返回时文件已可读
    companion object { const val ROTATION_BYTES: Long = 100L * 1024 * 1024 }
}
```

- [ ] **Step 1: 写失败测试（先全部写好）**

`core/src/test/kotlin/com/adbgui/core/device/LogcatRecorderTest.kt`：

```kotlin
package com.adbgui.core.device

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogcatRecorderTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun recorder(
        runner: FakeAdbProcessRunner, scope: CoroutineScope, file: Path,
        rotationBytes: Long = LogcatRecorder.ROTATION_BYTES,
    ): LogcatRecorder {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
        return LogcatRecorder(cmd, NoopLogger, scope, io = Dispatchers.Unconfined, file = file, rotationBytes = rotationBytes)
    }

    private val line1 = "08-17 10:23:45.100  100  200 I Tag1: hello"
    private val line2 = "08-17 10:23:45.200  100  201 I Tag2: world"

    @Test fun writes_all_lines_unbounded_and_readable_after_stop() = runTest {
        val runner = FakeAdbProcessRunner()
        // 远超任何 UI ring 上限的行数——录制不受 ringCap 约束（spec D2/D3）
        runner.setStreamLines((1..2000).map { "08-17 10:23:45.100  100  200 I Tag: line$it" })
        val dir = Files.createTempDirectory("adbgui-rec")
        val f = dir.resolve("r.log")
        val r = recorder(runner, this, f)
        r.start("abc")
        advanceUntilIdle()
        assertEquals(RecordStatus.RECORDING, r.state.value.status)
        r.stop(null)
        assertEquals(RecordStatus.STOPPED, r.state.value.status)
        val text = Files.readString(f)
        assertEquals(2000, text.lines().count { it.isNotBlank() })
        assertTrue(text.contains("line1\n"))            // 完整无截断
        assertTrue(text.endsWith("line2000\n"))         // \n 结尾、无 \r\n
    }

    @Test fun reconnect_after_eof_appends_marker_and_new_lines() = runTest {
        val runner = FakeAdbProcessRunner()
        // once：发完行就 EOF（通道关闭）→ runLoop 走重连路径；
        // 第二次 startStream 走 setStreamLines 的持久通道，行再次可用（Fake 的行为）
        runner.setStreamLinesOnce(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val f = dir.resolve("r.log")
        val r = recorder(runner, this, f)
        r.start("abc")
        advanceUntilIdle()                 // 收 line1，EOF，进入 backoff(1s)
        advanceTimeBy(1500); runCurrent()  // 重连 + 写 marker
        runner.emitStreamLine(line2)       // 第二个通道（未关闭）
        advanceUntilIdle()
        r.stop(null)
        val text = Files.readString(f)
        assertTrue(text.contains(line1))
        assertTrue(text.contains("--------- adbgui reconnect"))
        assertTrue(text.endsWith(line2 + "\n"))
    }

    @Test fun rotation_stops_recorder_in_place_when_file_full() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(line1, line2))
        val dir = Files.createTempDirectory("adbgui-rec")
        val f = dir.resolve("r.log")
        // line1(≈40 chars) 超阈值、空行不超 → 写完 line1 即轮转
        val r = recorder(runner, this, f, rotationBytes = 10)
        r.start("abc")
        advanceUntilIdle()
        assertTrue(r.state.value.rotated)
        assertEquals(RecordStatus.STOPPED, r.state.value.status)
        val text = Files.readString(f)
        assertTrue(text.contains(line1))
        assertFalse(text.contains(line2))   // 轮转后不再写旧文件
    }

    @Test fun write_failure_sets_error_and_stops() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val f = dir.resolve("occupied")     // 目标是个目录 → newBufferedWriter 抛 IOException
        Files.createDirectories(f)
        val r = recorder(runner, this, f)
        r.start("abc")
        advanceUntilIdle()
        assertEquals(RecordStatus.STOPPED, r.state.value.status)
        assertEquals("write_failed", r.state.value.stopReason)
        assertTrue(r.state.value.error != null)
    }

    @Test fun stop_reason_is_preserved_in_state() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val r = recorder(runner, this, dir.resolve("r.log"))
        r.start("abc")
        advanceUntilIdle()
        r.stop("device_disconnected")
        assertEquals("device_disconnected", r.state.value.stopReason)
        assertEquals(RecordStatus.STOPPED, r.state.value.status)
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecorderTest"`
Expected: 编译失败（`LogcatRecorder` 未定义）。

- [ ] **Step 3: 最小实现**

`core/src/main/kotlin/com/adbgui/core/device/LogcatRecorder.kt`：

```kotlin
package com.adbgui.core.device

import com.adbgui.core.adb.AdbStream
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

enum class RecordStatus { RECORDING, STOPPED }

data class RecordState(
    val status: RecordStatus = RecordStatus.STOPPED,
    val lineCount: Long = 0,
    val bytesWritten: Long = 0,
    val error: String? = null,
    /** 文件写满 [LogcatRecorder.rotationBytes] 后 recorder 原地收尾（spec §3.1）。
     *  manager 看到它就续开新文件（spec §3.2）。 */
    val rotated: Boolean = false,
    /** 机器可读的结束原因（"user" / "device_disconnected" / "write_failed"），UI 层映射文案。 */
    val stopReason: String? = null,
)

/** 一个 adb logcat 流 → 一个文件。原始行直写（不解析、不过滤，spec D2），无行数/时长上限。
 *  断流退避重连（1s→…→30s，复用 LogcatController.runLoop 模式）；单文件写满 rotationBytes
 *  即原地收尾（[RecordState.rotated]），不自续命——续开由 manager 负责。 */
class LogcatRecorder(
    private val commands: CommandRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val file: Path,
    private val rotationBytes: Long = ROTATION_BYTES,
) {
    private val _state = MutableStateFlow(RecordState())
    val state: StateFlow<RecordState> = _state.asStateFlow()

    // 跨线程：stop()（UI/manager 线程）读写，runLoop（io 线程）读写 → @Volatile（技术债规范 #3）
    @Volatile private var stream: AdbStream? = null
    @Volatile private var writer: BufferedWriter? = null
    private var job: Job? = null

    fun start(serial: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                withContext(io) {
                    Files.createDirectories(file.parent)
                    writer = Files.newBufferedWriter(
                        file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
                _state.value = RecordState(status = RecordStatus.RECORDING)
                runLoop(serial)
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    logger.warn("logcat recorder failed for $serial: ${t.message}")
                    _state.value = _state.value.copy(
                        status = RecordStatus.STOPPED, error = t.message, stopReason = "write_failed"
                    )
                }
            } finally {
                // NonCancellable：job 被 stop() 取消时仍要 flush+close，保证已写行落盘
                // （spec §3.1：stop 必须保证已写行全部落盘）。
                val w = writer
                writer = null
                withContext(NonCancellable + io) { runCatching { w?.close() } }
                if (w != null && _state.value.status == RecordStatus.RECORDING) {
                    _state.value = _state.value.copy(status = RecordStatus.STOPPED)
                }
            }
        }
    }

    suspend fun stop(reason: String?) {
        if (reason != null) _state.value = _state.value.copy(stopReason = reason)
        val j = job
        j?.cancel()
        stream?.kill(); stream = null
        j?.join()   // 等 finally（close）跑完 —— 返回时文件已可读（测试与 UI banner 依赖此语义）
    }

    /** 整个循环跑在 io 上：写文件无每行上下文切换；delay 在任何 dispatcher 上语义一致。 */
    private suspend fun runLoop(serial: String) {
        withContext(io) {
            launch { flushLoop() }   // 子协程：随 job 一起取消
            var backoff = 1000L
            while (true) {
                try {
                    val s = commands.streamLogcat(serial)
                    stream = s
                    for (line in s.lines) {
                        val w = writer ?: return@withContext
                        w.write(line); w.write("\n")   // 显式 \n，不用 appendLine（避免 \r\n）
                        val bytes = _state.value.bytesWritten + line.length + 1L
                        _state.value = _state.value.copy(
                            lineCount = _state.value.lineCount + 1, bytesWritten = bytes
                        )
                        if (bytes >= rotationBytes) {
                            w.flush()
                            _state.value = _state.value.copy(rotated = true)
                            return@withContext   // finally 关文件；续开是 manager 的事
                        }
                    }
                    logger.warn("logcat record stream ended for $serial (reconnecting)")
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logger.warn("logcat record stream error for $serial: ${t.message}")
                    _state.value = _state.value.copy(error = t.message)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                writer?.write("--------- adbgui reconnect $serial\n")
            }
        }
    }

    /** 每 1s flush：崩溃最多丢尾 1 秒（spec §7）。BufferedWriter 内部同步，与 write 并发安全。 */
    private suspend fun flushLoop() {
        while (true) {
            delay(FLUSH_INTERVAL_MS)
            runCatching { writer?.flush() }
        }
    }

    companion object {
        const val ROTATION_BYTES: Long = 100L * 1024 * 1024
        private const val FLUSH_INTERVAL_MS = 1000L
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecorderTest"`
Expected: 5 个测试全 PASS。若 `writes_all_lines...` 在 advanceUntilIdle 后仍 RECORDING，检查 Fake 通道是否 UNLIMITED 一次性发完（应同步发完）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/LogcatRecorder.kt core/src/test/kotlin/com/adbgui/core/device/LogcatRecorderTest.kt
git commit -m "feat(core): LogcatRecorder tees raw logcat lines to file with reconnect and rotation"
```

---

### Task 2: `Settings.logcatAutoRecord` + 设置写入

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt:48-52`（`Settings` data class，加在 `logcatRingCap` 之后）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsViewModel.kt:29` 附近（加 setter）
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/SettingsViewModelTest.kt`（追加用例）

**Interfaces:**
- Produces: `Settings.logcatAutoRecord: Boolean = true`（Task 3/4 的 manager 消费）；`SettingsViewModel.setLogcatAutoRecord(b: Boolean)`。

- [ ] **Step 1: 写失败测试**

在 `SettingsViewModelTest.kt` 追加（沿用该文件现有的 store/tmpdir 惯例；若文件里已有 `store(...)` 辅助构造则复用）：

```kotlin
@Test fun setLogcatAutoRecord_persists() = runTest {
    val dir = Files.createTempDirectory("adbgui-set")
    val store = SettingsStore(dir, io = Dispatchers.Unconfined)
    val vm = SettingsViewModel(store, backgroundScope)
    vm.setLogcatAutoRecord(false)
    advanceUntilIdle()
    assertEquals(false, store.state.value.logcatAutoRecord)
    // 序列化默认值（true）也不丢
    vm.setLogcatAutoRecord(true)
    advanceUntilIdle()
    assertEquals(true, store.state.value.logcatAutoRecord)
}
```

（import 按 Kotlin 默认可省；若该文件未 import `kotlin.test.assertEquals`/`kotlinx.coroutines.test.*` 则补上。）

- [ ] **Step 2: 验证失败**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.SettingsViewModelTest"`
Expected: 编译失败（`logcatAutoRecord` / `setLogcatAutoRecord` 未定义）。

- [ ] **Step 3: 实现**

`SettingsStore.kt` 的 `Settings`（`logcatRingCap` 字段后）：

```kotlin
    /** 设备上线时自动后台录制其 logcat 到 logcat-recordings/（"行车记录仪"，spec §3.3）。
     *  仅应用运行期间有效；auto 文件保留 7 天、单文件 100 MB 轮转。 */
    val logcatAutoRecord: Boolean = true,
```

`SettingsViewModel.kt`（`setLogcatRingCap` 后）：

```kotlin
    fun setLogcatAutoRecord(b: Boolean) = scope.launch { store.update { it.copy(logcatAutoRecord = b) } }
```

- [ ] **Step 4: 验证通过**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.SettingsViewModelTest"`
Expected: PASS（含新用例与既有用例）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsViewModel.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/SettingsViewModelTest.kt
git commit -m "feat(settings): logcatAutoRecord toggle (default on)"
```

---

### Task 3: `LogcatRecordingManager` —— 手动录制部分

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `LogcatRecorder`/`RecordState`/`RecordStatus`；`IDeviceTracker`（`DeviceRepository.kt:27`，`devices: StateFlow<List<DeviceSnapshot>>`）；`SettingsStore.state`；Task 2 的 `Settings.logcatAutoRecord`。
- Produces（Task 4/5 依赖）:

```kotlin
sealed interface ManualRecordState {
    data object Idle : ManualRecordState
    data class Recording(val serial: String, val file: Path, val lineCount: Long, val bytesWritten: Long) : ManualRecordState
    data class Stopped(val file: Path, val lineCount: Long, val bytesWritten: Long, val reason: String?) : ManualRecordState
}

class LogcatRecordingManager(
    commands: CommandRunner, tracker: IDeviceTracker, settings: SettingsStore, logger: Logger,
    scope: CoroutineScope, val recordingsDir: Path,
    io: CoroutineDispatcher = Dispatchers.IO,
    clock: () -> Long = { System.currentTimeMillis() },      // Task 4 清理测试注入
    rotationBytes: Long = LogcatRecorder.ROTATION_BYTES,     // Task 4 轮转测试注入
) {
    val manualState: StateFlow<ManualRecordState>
    val autoStates: StateFlow<Map<String, RecordState>>      // Task 4 实现，本任务恒为空 map
    fun startManual(serial: String)      // 已在录则忽略
    suspend fun stopManual(reason: String)
    fun stop()                           // 停一切（对称 DeviceRepository.stop()）
}
```

- [ ] **Step 1: 写失败测试（手动部分）**

`core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt`：

```kotlin
package com.adbgui.core.device

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.log.NoopLogger
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogcatRecordingManagerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private class FakeTracker : IDeviceTracker {
        override val devices = MutableStateFlow<List<DeviceSnapshot>>(emptyList())
    }

    private val line1 = "08-17 10:23:45.100  100  200 I Tag1: hello"

    private fun manager(
        runner: FakeAdbProcessRunner, scope: kotlinx.coroutines.CoroutineScope, dir: java.nio.file.Path,
        tracker: FakeTracker = FakeTracker(),
    ): LogcatRecordingManager {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        return LogcatRecordingManager(cmd, tracker, settings, NoopLogger, scope, dir, io = Dispatchers.Unconfined)
    }

    @Test fun manual_record_lifecycle_and_filename_sanitization() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val m = manager(runner, backgroundScope, dir)
        assertEquals(ManualRecordState.Idle, m.manualState.value)
        m.startManual("192.168.1.5:5555")     // 无线 serial 含 ':'
        advanceUntilIdle()
        val rec = m.manualState.value
        assertTrue(rec is ManualRecordState.Recording)
        assertEquals("192.168.1.5:5555", rec.serial)
        assertTrue(rec.file.fileName.toString().startsWith("manual_192.168.1.5_5555_"))
        assertTrue(rec.file.fileName.toString().endsWith(".log"))
        m.stopManual("user")
        advanceUntilIdle()
        val stopped = m.manualState.value
        assertTrue(stopped is ManualRecordState.Stopped)
        assertEquals("user", stopped.reason)
        assertTrue(Files.exists(stopped.file))
        assertTrue(Files.readString(stopped.file).contains(line1))
    }

    @Test fun startManual_while_recording_is_ignored() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val m = manager(runner, backgroundScope, dir)
        m.startManual("abc")
        advanceUntilIdle()
        val first = (m.manualState.value as ManualRecordState.Recording).serial
        m.startManual("other")                // 一次最多一个会话（spec §3.2）
        assertEquals(first, (m.manualState.value as ManualRecordState.Recording).serial)
        m.stopManual("user")
    }

    @Test fun stop_all_stops_manual() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val m = manager(runner, backgroundScope, dir)
        m.startManual("abc")
        advanceUntilIdle()
        m.stop()
        advanceUntilIdle()
        assertTrue(m.manualState.value is ManualRecordState.Stopped)
    }
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecordingManagerTest"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现（手动部分 + 空的 auto 部分）**

`core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt`：

```kotlin
package com.adbgui.core.device

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.log.Logger
import com.adbgui.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ManualRecordState {
    data object Idle : ManualRecordState
    data class Recording(val serial: String, val file: java.nio.file.Path, val lineCount: Long, val bytesWritten: Long) : ManualRecordState
    data class Stopped(val file: java.nio.file.Path, val lineCount: Long, val bytesWritten: Long, val reason: String?) : ManualRecordState
}

/** 录制编排层（spec §3.2）：手动会话（一次一个）+ 自动后台录制 + 启动清理 + 轮转续开。 */
class LogcatRecordingManager(
    private val commands: CommandRunner,
    private val tracker: IDeviceTracker,
    private val settings: SettingsStore,
    private val logger: Logger,
    private val scope: CoroutineScope,
    val recordingsDir: java.nio.file.Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rotationBytes: Long = LogcatRecorder.ROTATION_BYTES,
) {
    private val _manualState = MutableStateFlow<ManualRecordState>(ManualRecordState.Idle)
    val manualState: StateFlow<ManualRecordState> = _manualState.asStateFlow()

    private val _autoStates = MutableStateFlow<Map<String, RecordState>>(emptyMap())
    val autoStates: StateFlow<Map<String, RecordState>> = _autoStates.asStateFlow()

    private var manualRecorder: LogcatRecorder? = null
    private var manualJob: Job? = null
    private val mutex = Mutex()
    private val autoRecorders = mutableMapOf<String, LogcatRecorder>()   // mutex 保护
    private val autoJobs = mutableMapOf<String, Job>()                   // mutex 保护
    private var observerJob: Job? = null

    fun startManual(serial: String) {
        if (_manualState.value is ManualRecordState.Recording) return
        val file = uniqueFile(recordingsDir.resolve("manual_${sanitizeSerial(serial)}_${stamp()}.log"))
        val rec = LogcatRecorder(commands, logger, scope, io, file, rotationBytes)
        manualJob?.cancel()
        manualRecorder = rec
        manualJob = scope.launch {
            rec.state.collect { st ->
                _manualState.value = when (st.status) {
                    RecordStatus.RECORDING ->
                        ManualRecordState.Recording(serial, file, st.lineCount, st.bytesWritten)
                    RecordStatus.STOPPED ->
                        ManualRecordState.Stopped(file, st.lineCount, st.bytesWritten, st.stopReason)
                }
            }
        }
        rec.start(serial)
    }

    suspend fun stopManual(reason: String) {
        manualRecorder?.stop(reason)   // collector 会把最终 STOPPED 状态发进 manualState
    }

    fun stop() {
        observerJob?.cancel(); observerJob = null
        manualJob?.cancel(); manualJob = null
        scope.launch { manualRecorder?.stop(null) }
        manualRecorder = null
        scope.launch {
            mutex.withLock {
                val serials = autoRecorders.keys.toList()
                serials.forEach { stopAutoLocked(it) }
            }
        }
    }

    companion object {
        const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** serial → 文件名安全段：':'（无线 serial）与 Windows 非法字符统一 '_'（spec §3.2）。 */
        fun sanitizeSerial(serial: String): String =
            serial.replace(":", "_").replace(Regex("""[\\/*?"<>|]""), "_")

        private fun stamp(): String =
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

        private fun uniqueFile(path: java.nio.file.Path): java.nio.file.Path {
            if (!java.nio.file.Files.exists(path)) return path
            var i = 1
            while (true) {
                val candidate = path.resolveSibling(
                    path.fileName.toString().removeSuffix(".log") + "_$i.log"
                )
                if (!java.nio.file.Files.exists(candidate)) return candidate
                i++
            }
        }
    }

    // --- Task 4 填充：observeDevices / startAutoLocked / stopAutoLocked / cleanupOldAutoFiles ---
    // 本任务先留空实现，保证编译（autoStates 恒为 emptyMap）：
    internal fun startAutoLocked(serial: String) { /* Task 4 */ }
    internal suspend fun stopAutoLocked(serial: String) { /* Task 4 */ }
    internal fun cleanupOldAutoFiles() { /* Task 4 */ }
}
```

（`stop()` 里对 `stopAutoLocked` 的调用在 Task 4 才有真实实现；本任务先保留空方法以满足编译——这不是死代码，Task 4 会立即填充。）

- [ ] **Step 4: 验证通过**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecordingManagerTest"`
Expected: 3 个测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt
git commit -m "feat(core): LogcatRecordingManager manual recording session"
```

---

### Task 4: `LogcatRecordingManager` —— 自动录制 + 清理 + 轮转续开

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt`（填充 Task 3 的三个空方法 + init 块）
- Test: `core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt`（追加用例）

**Interfaces:**
- Consumes: Task 1-3 的全部产物。
- Produces: manager 完整行为（Task 5 的 VM/`Main.kt` 依赖完整构造签名）。

- [ ] **Step 1: 写失败测试（追加到 `LogcatRecordingManagerTest`）**

```kotlin
    // --- auto recording ---

    @Test fun auto_record_starts_and_stops_with_device_online_state() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, backgroundScope, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        val auto = dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }
        assertEquals(1, auto?.size)
        assertTrue(Files.readString(auto[0].toPath()).contains(line1))
        assertTrue(m.autoStates.value.containsKey("abc"))
        tracker.devices.value = emptyList()   // 设备下线
        advanceUntilIdle()
        assertTrue(!m.autoStates.value.containsKey("abc"))
    }

    @Test fun auto_record_disabled_setting_starts_nothing() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, backgroundScope, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        settings.update { it.copy(logcatAutoRecord = false) }
        val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, backgroundScope, dir, io = Dispatchers.Unconfined)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        assertEquals(0, dir.toFile().listFiles { f -> f.name.startsWith("auto_") }?.size)
        // 运行时打开开关 → 立即补启（spec §3.2）
        settings.update { it.copy(logcatAutoRecord = true) }
        advanceUntilIdle()
        assertEquals(1, dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }?.size)
    }

    @Test fun manual_recording_stops_when_device_disconnects() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, backgroundScope, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        m.startManual("abc")
        advanceUntilIdle()
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.OFFLINE))
        advanceUntilIdle()
        val st = m.manualState.value
        assertTrue(st is ManualRecordState.Stopped)
        assertEquals("device_disconnected", st.reason)
    }

    @Test fun rotated_auto_recorder_is_replaced_with_a_new_file() = runTest {
        val runner = FakeAdbProcessRunner()
        // once：第一个 recorder 收 2 行后 EOF；后续 recorder 拿到空流（streamLines 默认空）不轮转 → 稳定
        runner.setStreamLinesOnce(listOf(line1, line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, backgroundScope, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        // line1 ≈ 40 chars：阈值取介于 1 行与 2 行之间 → 写完第 1 行即轮转
        val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, backgroundScope, dir,
            io = Dispatchers.Unconfined, rotationBytes = line1.length + 2L)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        // recorder1 轮转收尾（1 行），manager 续开 recorder2（空流、存活）→ auto_ 文件共 2 个
        val autoFiles = dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }
        assertEquals(2, autoFiles?.size)
        val lineCounts = autoFiles!!.map { Files.readString(it.toPath()).lines().count { l -> l.isNotBlank() } }
        assertEquals(1, lineCounts.count { it == 1L })
        m.stop()
    }

    // --- startup cleanup ---

    @Test fun startup_cleanup_deletes_old_auto_but_keeps_manual() = runTest {
        val runner = FakeAdbProcessRunner()
        val dir = Files.createTempDirectory("adbgui-rec")
        val oldAuto = dir.resolve("auto_old.log"); oldAuto.writeText("x")
        val oldManual = dir.resolve("manual_old.log"); oldManual.writeText("x")
        val now = System.currentTimeMillis()
        oldAuto.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        oldManual.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, backgroundScope, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        settings.update { it.copy(logcatAutoRecord = false) }   // 别让 auto 录制添新文件
        LogcatRecordingManager(cmd, tracker, settings, NoopLogger, backgroundScope, dir,
            io = Dispatchers.Unconfined, clock = { now })
        advanceUntilIdle()
        assertTrue(!Files.exists(oldAuto))     // 8 天前 auto → 删
        assertTrue(Files.exists(oldManual))    // manual 永不自动删（spec §3.2）
    }
```

（`java.nio.file.Files`、`kotlin.io.path.writeText` 等 import 若缺则补。）

- [ ] **Step 2: 验证失败**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecordingManagerTest"`
Expected: 编译失败或新增用例 FAIL（空实现不产生 auto 文件 / 不清理）。

- [ ] **Step 3: 实现**

`LogcatRecordingManager.kt` —— init 块（构造时启动）：

```kotlin
    init {
        // 启动清理（spec §3.2）：删 7 天前 auto 文件；失败仅 WARN，不阻塞
        scope.launch {
            withContext(io) { runCatching { cleanupOldAutoFiles() } }
                .onFailure { logger.warn("recording cleanup failed: ${it.message}") }
        }
        observerJob = scope.launch { observeDevices() }
    }
```

（需要 `import kotlinx.coroutines.withContext`。）填充三个方法：

```kotlin
    /** 设备上下线 + 设置开关 → 增删 auto recorder；同时守护手动会话（设备断开即停）。 */
    private suspend fun observeDevices() {
        kotlinx.coroutines.flow.combine(settings.state, tracker.devices) { s, devs ->
            s.logcatAutoRecord to devs
        }.collect { (autoEnabled, devs) ->
            val online = devs.filter { it.status == com.adbgui.core.domain.DeviceStatus.ONLINE }
                .map { it.serial }.toSet()
            val manual = _manualState.value
            if (manual is ManualRecordState.Recording && manual.serial !in online) {
                stopManual("device_disconnected")
            }
            mutex.withLock {
                autoRecorders.keys.filter { it !in online }.forEach { stopAutoLocked(it) }
                if (autoEnabled) online.filter { it !in autoRecorders }.forEach { startAutoLocked(it) }
                else autoRecorders.keys.toList().forEach { stopAutoLocked(it) }
            }
        }
    }

    /** mutex 持有中调用。轮转续开：recorder 写满文件原地收尾后，这里换新文件重开（spec §3.2）。 */
    internal fun startAutoLocked(serial: String) {
        val file = uniqueFile(recordingsDir.resolve("auto_${sanitizeSerial(serial)}_${stamp()}.log"))
        val rec = LogcatRecorder(commands, logger, scope, io, file, rotationBytes)
        autoRecorders[serial] = rec
        autoJobs[serial] = scope.launch {
            rec.state.collect { st ->
                _autoStates.value = _autoStates.value + (serial to st)
                if (st.rotated) {
                    mutex.withLock {
                        if (autoRecorders[serial] === rec) {
                            autoJobs.remove(serial)?.cancel()
                            startAutoLocked(serial)
                        }
                    }
                }
            }
        }
        rec.start(serial)
    }

    /** mutex 持有中调用（suspend：内部 join recorder 的收尾）。 */
    internal suspend fun stopAutoLocked(serial: String) {
        autoJobs.remove(serial)?.cancel()
        autoRecorders.remove(serial)?.stop(null)
        _autoStates.value = _autoStates.value - serial
    }

    /** 删除超过 [RETENTION_MS] 的 auto_*.log；manual_*.log 永不动（spec §3.2）。io 上执行。 */
    internal fun cleanupOldAutoFiles() {
        val dir = recordingsDir.toFile()
        if (!dir.isDirectory) return
        val cutoff = clock() - RETENTION_MS
        dir.listFiles { f -> f.isFile && f.name.startsWith("auto_") && f.name.endsWith(".log") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }
```

同时把 Task 3 留的空方法签名替换为上面的真实实现（删除空体）。`stop()` 里对 `stopAutoLocked` 的 `scope.launch` 包裹保持不变。

- [ ] **Step 4: 验证通过**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.LogcatRecordingManagerTest"`
Expected: 全部 8 个测试 PASS（Task 3 的 3 个 + 本任务 5 个）。

再跑全量防回归：`./gradlew :core:test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/LogcatRecordingManager.kt core/src/test/kotlin/com/adbgui/core/device/LogcatRecordingManagerTest.kt
git commit -m "feat(core): auto logcat recording with device lifecycle, rotation handoff, retention cleanup"
```

---

### Task 5: `LogcatViewModel` 扩展 + `Main.kt` 接线

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatViewModel.kt:16-24`（构造函数加参数）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt:76-84`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/LogcatViewModelTest.kt`（改构造 + 新用例）

**Interfaces:**
- Consumes: Task 3/4 的 `LogcatRecordingManager`、`ManualRecordState`。
- Produces（Task 6 的 Screen 依赖）:

```kotlin
// LogcatViewModel 新增：
val manualRecord: StateFlow<ManualRecordState>        // = recording.manualState
val autoRecordStates: StateFlow<Map<String, RecordState>>
val recordingsDir: java.nio.file.Path                 // get() = recording.recordingsDir
val recordError: StateFlow<String?>                   // 无设备时 startRecording 的内联错误
fun startRecording()                                  // 无设备 → recordError；有 → recording.startManual
fun stopRecording()                                   // scope.launch { recording.stopManual("user") }
```

- [ ] **Step 1: 写失败测试（改 `LogcatViewModelTest`）**

先更新该文件所有现有构造调用：`LogcatViewModel(controller, selectedSerial, deviceOnline, scope)` → 插入第 2 个参数 `recording`。构造辅助（文件顶部/类内）：

```kotlin
private fun recordingManager(
    runner: FakeAdbProcessRunner, scope: kotlinx.coroutines.CoroutineScope,
): com.adbgui.core.device.LogcatRecordingManager {
    val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
    val dir = java.nio.file.Files.createTempDirectory("adbgui-rec")
    return com.adbgui.core.device.LogcatRecordingManager(
        cmd, FakeTracker(), SettingsStore(dir, io = Dispatchers.Unconfined), NoopLogger,
        backgroundScope, dir, io = Dispatchers.Unconfined,
    ).also { it /* autoRecord 默认 true 但 FakeTracker 无设备 → 不会起任何流 */ }
}
```

（`FakeTracker` 复制 `LogcatRecordingManagerTest` 的那个小类；`SettingsStore`/`Dispatchers` import 补齐。）

追加用例：

```kotlin
@Test fun startRecording_without_device_sets_inline_error() = runTest {
    val vm = LogcatViewModel(controller(FakeAdbProcessRunner(), backgroundScope),
        recordingManager(FakeAdbProcessRunner(), backgroundScope),
        MutableStateFlow(null), MutableStateFlow(false), backgroundScope)
    vm.startRecording()
    assertEquals(true, vm.recordError.value != null)
    assertEquals(com.adbgui.core.device.ManualRecordState.Idle, vm.manualRecord.value)
}

@Test fun start_stop_recording_transitions_manual_state() = runTest {
    val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: m"))
    val serial = MutableStateFlow("abc")
    val vm = LogcatViewModel(controller(runner, backgroundScope),
        recordingManager(runner, backgroundScope), serial, MutableStateFlow(true), backgroundScope)
    vm.startRecording()
    advanceUntilIdle()
    assertTrue(vm.manualRecord.value is com.adbgui.core.device.ManualRecordState.Recording)
    vm.stopRecording()
    advanceUntilIdle()
    val st = vm.manualRecord.value
    assertTrue(st is com.adbgui.core.device.ManualRecordState.Stopped)
    assertEquals("user", st.reason)
}
```

- [ ] **Step 2: 验证失败**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.LogcatViewModelTest"`
Expected: 编译失败（构造参数不匹配）。

- [ ] **Step 3: 实现**

`LogcatViewModel.kt`：

```kotlin
class LogcatViewModel(
    private val controller: LogcatController,
    private val recording: com.adbgui.core.device.LogcatRecordingManager,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    val deviceOnline: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
```

新增成员（放在 export 状态组之后）：

```kotlin
    // --- recording (spec §3.4): forwards only, no adb access (红线 #2) ---
    val manualRecord: StateFlow<com.adbgui.core.device.ManualRecordState> = recording.manualState
    val autoRecordStates: StateFlow<Map<String, com.adbgui.core.device.RecordState>> = recording.autoStates
    val recordingsDir: java.nio.file.Path get() = recording.recordingsDir

    private val _recordError = MutableStateFlow<String?>(null)
    val recordError: StateFlow<String?> = _recordError.asStateFlow()

    fun startRecording() {
        val serial = selectedSerial.value
        if (serial == null) {
            _recordError.value = Strings.t("no_device_selected_logcat")
            return
        }
        _recordError.value = null
        recording.startManual(serial)
    }

    fun stopRecording() { scope.launch { recording.stopManual("user") } }
```

`Main.kt:84` 前插入（`logcatController` 之后）：

```kotlin
    val logcatRecording = remember {
        com.adbgui.core.device.LogcatRecordingManager(
            root.commands, root.tracker, root.settings, root.logger, root.scope,
            root.configDir.resolve("logcat-recordings"),
        )
    }
```

`Main.kt:84` 改为：

```kotlin
    val logcatVm = remember { LogcatViewModel(logcatController, logcatRecording, selectedSerial, deviceOnline, root.scope) }
```

- [ ] **Step 4: 验证通过**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.LogcatViewModelTest"`
Expected: PASS（新用例 + 全部既有用例）。再跑 `./gradlew :desktop:test` 防其它 VM 测试受构造变化影响。

- [ ] **Step 5: 提交**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatViewModel.kt desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt desktop/src/test/kotlin/com/adbgui/desktop/ui/LogcatViewModelTest.kt
git commit -m "feat(desktop): wire LogcatRecordingManager into LogcatViewModel and Main"
```

---

### Task 6: UI —— Logcat 页录制控件 + 设置开关 + i18n

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt`（工具栏 108-140 行区域 + banner 区域 148-153）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt`（ring cap 段落后）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`（zh 块 ~202 行区域 + en 块 ~639 行区域）

**Interfaces:**
- Consumes: Task 5 的 VM API；现有 `SavedFileBanner`/`openFile`/`revealFile`/`InlineMessageBanner`（`LogcatScreen.kt:148-153` 已用）；`SettingsViewModel.setLogcatAutoRecord`。

- [ ] **Step 1: i18n keys**

`Strings.kt` zh 块（`"export_progress"` 附近）追加：

```kotlin
        "record_start" to "开始录制",
        "record_stop" to "停止录制",
        "recording_status" to "录制中 · %d 行 · %s",
        "record_stopped_device_disconnected" to "设备断开，录制已停止",
        "record_stopped_write_failed" to "录制失败，文件已保留已写入部分",
        "auto_recording" to "后台录制中",
        "open_recordings_folder" to "打开录制文件夹",
        "logcat_auto_record" to "自动录制设备日志",
        "logcat_auto_record_desc" to "设备上线时在后台录制其 logcat（应用需保持运行）。文件保留 7 天、单文件 100 MB 自动轮转。",
```

en 块对应追加：

```kotlin
        "record_start" to "Start recording",
        "record_stop" to "Stop recording",
        "recording_status" to "Recording · %d lines · %s",
        "record_stopped_device_disconnected" to "Device disconnected — recording stopped",
        "record_stopped_write_failed" to "Recording failed; the partially written file was kept",
        "auto_recording" to "Background recording",
        "open_recordings_folder" to "Open recordings folder",
        "logcat_auto_record" to "Auto-record device logs",
        "logcat_auto_record_desc" to "Start recording a device's logcat in the background when it comes online (the app must stay running). Files are kept for 7 days; each file rotates at 100 MB.",
```

- [ ] **Step 2: LogcatScreen 工具栏**

imports 补：`androidx.compose.material.icons.filled.FiberManualRecord`、`androidx.compose.material.icons.filled.Stop`、`com.adbgui.core.device.ManualRecordState`、`com.adbgui.core.device.RecordStatus`。

在 composable 顶部状态收集（`savedPath` 行后）：

```kotlin
    val manualRecord by vm.manualRecord.collectAsState()
    val recordError by vm.recordError.collectAsState()
    val autoRecordStates by vm.autoRecordStates.collectAsState()
```

工具栏 Row 里，pause 按钮（`LogcatScreen.kt:110`）之后插入：

```kotlin
                val recordingNow = manualRecord is ManualRecordState.Recording
                Button(
                    enabled = recordingNow || deviceOnline,
                    colors = if (recordingNow) ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error
                    ) else ButtonDefaults.buttonColors(),
                    onClick = { if (recordingNow) vm.stopRecording() else vm.startRecording() },
                ) {
                    Icon(
                        if (recordingNow) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = Strings.t(if (recordingNow) "record_stop" else "record_start"),
                        tint = if (!recordingNow) MaterialTheme.colors.error else contentColorFor(MaterialTheme.colors.error),
                    )
                    if (recordingNow) {
                        Spacer(Modifier.width(4.dp))
                        val r = manualRecord as ManualRecordState.Recording
                        Text(Strings.t("recording_status").format(r.lineCount, formatBytes(r.bytesWritten)))
                    }
                }
```

（`contentColorFor` 来自 `androidx.compose.material`，已在通配 import 里；`ButtonDefaults` 同。）

banner 区域（`savedPath?.let` 之前）插入录制相关提示：

```kotlin
            recordError?.let { msg -> InlineMessageBanner(msg, MessageKind.Error) }
            when (val mr = manualRecord) {
                is ManualRecordState.Stopped -> {
                    if (mr.reason == "device_disconnected") {
                        InlineMessageBanner(Strings.t("record_stopped_device_disconnected"), MessageKind.Warning)
                    } else if (mr.reason == "write_failed") {
                        InlineMessageBanner(Strings.t("record_stopped_write_failed"), MessageKind.Warning)
                    }
                    SavedFileBanner(
                        path = mr.file.toFile().absolutePath,
                        onOpen = { openFile(mr.file.toFile()) },
                        onReveal = { revealFile(mr.file.toFile()) },
                    )
                }
                else -> {}
            }
            if (autoRecordStates.isNotEmpty()) {
                Text(
                    Strings.t("auto_recording"),
                    Modifier.padding(start = 4.dp).clickable { revealFile(File(vm.recordingsDir.toAbsolutePath().toString())) }
                        .padding(4.dp),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary,
                )
            }
```

（import `androidx.compose.foundation.clickable`。`MessageKind.Warning` 若枚举无此值，用 `MessageKind.Error` 现有值并在 Strings 文案里弱化——先查 `InlineMessageBanner` 的 `MessageKind` 定义再定。）

文件底部（`openFile`/`revealFile` 辅助函数附近）加：

```kotlin
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
```

- [ ] **Step 3: SettingsScreen 开关**

`logcatRingCap` 段落（`SettingsScreen.kt:220` 起）之后插入：

```kotlin
            Divider()

            // --- Auto logcat recording ---
            Text(Strings.t("logcat_auto_record"), style = MaterialTheme.typography.subtitle1)
            Text(Strings.t("logcat_auto_record_desc"), style = MaterialTheme.typography.caption)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.logcatAutoRecord,
                    onCheckedChange = { settingsVm.setLogcatAutoRecord(it) },
                )
            }
```

（`Switch`、`Alignment`、`Row` 若未 import 则补 `androidx.compose.material.Switch`。）

- [ ] **Step 4: 编译 + 全量测试**

Run: `./gradlew :desktop:test :core:test`
Expected: 全部 PASS（编译通过；UI 层无新增单测——按项目惯例 VM 单测已覆盖状态机，Compose 层不强制）。

- [ ] **Step 5: 真机冒烟（用户配合）**

Run: 杀掉旧 MainKt JVM 后 `./gradlew :desktop:run`，连接真机验证：
1. Logcat 页点"开始录制"→ 按钮变 ■ + 行数增长 → 切到 App 管理页再切回，录制仍在进行（跨页面存活）→ 点停止 → banner 打开文件。
2. 拔掉设备 → 录制自动停止 + "设备断开"提示。
3. 设置页关/开"自动录制设备日志" → `%APPDATA%/AdbGui/logcat-recordings/` 里 auto 文件出现/停止增长。
4. 文件抽查：录制期间设备上的日志（对比 `adb logcat -d` 相应时间段）都在文件里，行尾是 `\n`。

- [ ] **Step 6: 提交**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/LogcatScreen.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt
git commit -m "feat(desktop): logcat recording UI - start/stop button, banners, settings toggle"
```

---

### Task 7: CHANGELOG + 收尾

**Files:**
- Modify: `CHANGELOG.md`（顶部新版本节，格式沿用既有节）

- [ ] **Step 1: 写 CHANGELOG 条目**

在 CHANGELOG.md 顶部（沿用现有节的标题/结构惯例）新增：

```markdown
## v2 — 2026-09-30 Logcat 录制（自动 + 手动）

分支：feat/logcat-recording。spec：docs/superpowers/specs/2026-09-30-logcat-recording-design.md

- **LogcatRecorder（core）**：独立 `adb logcat` 进程逐行把原始行（不解析/不过滤/无上限）tee 到文件；断流退避重连（重连处写 `--------- adbgui reconnect <serial>` 标记行）；单文件 100 MB 轮转；stop 保证已写行落盘。
- **手动录制**：Logcat 页 ●/■ 按钮，录制跨页面存活、设备断开自动停止；文件 `manual_<serial>_<stamp>.log`。
- **自动后台录制**（默认开，设置可关）：设备上线即录、下线即停，文件 `auto_<serial>_<stamp>.log`，7 天过期清理（manual 文件永不自动删）。应用需保持运行。
- 存放：`%APPDATA%/AdbGui/logcat-recordings/`，工具栏"后台录制中"chip 可直达。
- 已知边界：TCL 等 logd 被禁用的设备录出空文件（沿用页面 fix logd 流程）；页面显示 ring 上限与录制无关。

待办：无（PairResultParser fixture 遗留见既有 CHANGELOG 节）。
```

- [ ] **Step 2: 死代码检查（技术债规范 #2）**

Run: `grep -rn "LogcatRecorder\|LogcatRecordingManager\|RecordState\|ManualRecordState" --include="*.kt" core desktop | grep -v test | grep -v "LogcatRecordingManager.kt\|LogcatRecorder.kt"`
Expected: 每个类在 `Main.kt`/VM 有引用；无只在定义处出现的符号。若有 → 删或接线。

- [ ] **Step 3: 提交**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for logcat recording"
```

---

## Self-Review 记录

- **Spec 覆盖**：§2 D1-D3 → Task 1（独立进程/原始行/ring 解耦）；§3.1 → Task 1；§3.2 → Task 3/4；§3.3 → Task 2/6；§3.4 → Task 5/6；§3.5 标记行 → Task 1 runLoop；§5 边界 → 无需任务（不做的事）；§6 错误处理 → Task 1（write_failed）+ Task 4（清理失败仅 WARN）；§7 性能 → Task 1（不解析/批量 flush/NonCancellable close）；§8 测试 11 项 → Task 1(5)+Task 4(5)+Task 5(2)+Task 2(1)；§9 顺序一致。无缺口。
- **占位符**：Task 3 的空方法是刻意过渡（Task 4 立即填充并在 Step 3 说明删除空体），非 TBD。
- **类型一致性**：`RecordState`/`ManualRecordState`/`sanitizeSerial`/`stopManual(reason)` 在 Task 1/3/4/5/6 间签名一致；`stopReason` 值 `"user"`/`"device_disconnected"`/`"write_failed"` 全程统一。
