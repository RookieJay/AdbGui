package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
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
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogcatRecordingManagerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private class FakeTracker : IDeviceTracker {
        override val devices = MutableStateFlow<List<DeviceSnapshot>>(emptyList())
    }

    private val line1 = "08-17 10:23:45.100  100  200 I Tag1: hello"

    /** 品牌型号安卓版本命名用 getprop 脚本输出（Parser 本身有独立 fixture，这里是 manager 层脚本）。 */
    private fun getpropOut(brand: String, model: String, version: String): String =
        "[ro.product.brand]: [$brand]\n" +
            "[ro.product.manufacturer]: [$brand]\n" +
            "[ro.product.model]: [$model]\n" +
            "[ro.build.version.release]: [$version]\n" +
            "[ro.build.version.sdk]: [23]\n"

    /** 空历史的设备存储（绝大多数用例不关心别名兜底）。 */
    private fun noHistory(p: java.nio.file.Path): DeviceHistoryStore =
        DeviceHistoryStore(p, clock = { 0L }, io = Dispatchers.Unconfined)

    private fun manager(
        runner: FakeAdbProcessRunner, scope: kotlinx.coroutines.CoroutineScope, dir: java.nio.file.Path,
        tracker: FakeTracker = FakeTracker(),
        history: DeviceHistoryStore = noHistory(dir),
    ): LogcatRecordingManager {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        return LogcatRecordingManager(cmd, tracker, settings, history, NoopLogger, scope, dir, io = Dispatchers.Unconfined)
    }

    @Test fun manual_record_lifecycle_and_filename_sanitization() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("192.168.1.5:5555", DeviceStatus.ONLINE))
        assertEquals(ManualRecordState.Idle, m.manualState.value)
        m.startManual("192.168.1.5:5555")     // 无线 serial 含 ':'
        advanceUntilIdle()
        val rec = m.manualState.value
        assertTrue(rec is ManualRecordState.Recording)
        assertEquals("192.168.1.5:5555", rec.serial)
        assertTrue(rec.file.fileName.toString().startsWith("192.168.1.5_5555_"))   // getprop 失败 → 回退 serial
        assertTrue(!rec.file.fileName.toString().startsWith("manual_"))            // 不再区分 manual 前缀
        assertTrue(rec.file.fileName.toString().endsWith(".log"))
        m.stopManual("user")
        advanceUntilIdle()
        val stopped = m.manualState.value
        assertTrue(stopped is ManualRecordState.Stopped)
        assertEquals("user", stopped.reason)
        assertTrue(Files.exists(stopped.file))
        assertTrue(Files.readString(stopped.file).contains(line1))
        m.stop()
        advanceUntilIdle()
    }

    @Test fun manual_filename_uses_brand_model_androidversion() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, getpropOut("TCL", "618D", "6.0"), ""))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("192.168.1.5:5555", DeviceStatus.ONLINE))
        m.startManual("192.168.1.5:5555")
        advanceUntilIdle()
        val rec = m.manualState.value
        assertTrue(rec is ManualRecordState.Recording)
        val name = rec.file.fileName.toString()
        assertTrue(name.startsWith("TCL_618D_Android6.0_"), "got: $name")   // 品牌_型号_Android版本，非 IP
        assertTrue(!name.startsWith("manual_"))
        m.stopManual("user")
        m.stop()
        advanceUntilIdle()
    }

    @Test fun manual_filename_falls_back_to_serial_when_props_unknown() = runTest {
        // getprop 成功但全是 unknown（Parser 缺 key 的默认值）→ 回退 serial
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, "", ""))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        m.startManual("abc")
        advanceUntilIdle()
        val rec = m.manualState.value
        assertTrue(rec is ManualRecordState.Recording)
        assertTrue(rec.file.fileName.toString().startsWith("abc_"))
        m.stopManual("user")
        m.stop()
        advanceUntilIdle()
    }

    @Test fun manual_filename_retries_getprop_before_falling_back() = runTest {
        // 冷启动竞态：getprop 前两次失败（adb connect 未就绪），第 3 次成功 → 应取到 label 而非回退 serial
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        var getpropCalls = 0
        runner.runSideEffect = { args ->
            if (args.any { it.contains("getprop") }) {
                getpropCalls++
                if (getpropCalls >= 3) {
                    runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, getpropOut("TCL", "618D", "6.0"), ""))
                }
            }
        }
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        m.startManual("abc")
        advanceUntilIdle()
        val rec = m.manualState.value
        assertTrue(rec is ManualRecordState.Recording)
        assertTrue(rec.file.fileName.toString().startsWith("TCL_618D_Android6.0_"),
            "got: ${rec.file.fileName}")   // 重试后 getprop 成功 → 正常 label，不落 IP/serial 命名
        m.stopManual("user")
        m.stop()
        advanceUntilIdle()
    }

    @Test fun manual_filename_falls_back_to_history_alias_when_getprop_fails() = runTest {
        // 设备垂死/离线：getprop 恒败，但历史里已有 auto-name 写入的别名 → 用别名，不落 IP
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val history = noHistory(dir)
        history.setAlias("192.168.1.5:5555", "hisense VIDAA_TV")
        val m = manager(runner, this, dir, tracker, history)
        tracker.devices.value = listOf(DeviceSnapshot("192.168.1.5:5555", DeviceStatus.ONLINE))
        m.startManual("192.168.1.5:5555")
        advanceUntilIdle()
        val rec = m.manualState.value
        assertTrue(rec is ManualRecordState.Recording)
        assertTrue(rec.file.fileName.toString().startsWith("hisense_VIDAA_TV_"),
            "got: ${rec.file.fileName}")   // 别名兜底（空格→下划线），而非 192.168.1.5_5555_
        m.stopManual("user")
        m.stop()
        advanceUntilIdle()
    }

    @Test fun manual_filename_falls_back_to_alias_when_props_unknown() = runTest {
        // getprop 成功但全是 unknown → 别名兜底，不落 serial
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, "", ""))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val history = noHistory(dir)
        history.setAlias("abc", "hisense VIDAA_TV")
        val m = manager(runner, this, dir, tracker, history)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        m.startManual("abc")
        advanceUntilIdle()
        val rec = m.manualState.value
        assertTrue(rec is ManualRecordState.Recording)
        assertTrue(rec.file.fileName.toString().startsWith("hisense_VIDAA_TV_"),
            "got: ${rec.file.fileName}")
        m.stopManual("user")
        m.stop()
        advanceUntilIdle()
    }

    @Test fun startManual_while_recording_is_ignored() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        m.startManual("abc")
        advanceUntilIdle()
        val first = (m.manualState.value as ManualRecordState.Recording).serial
        m.startManual("other")                // 一次最多一个会话（spec §3.2）
        assertEquals(first, (m.manualState.value as ManualRecordState.Recording).serial)
        m.stopManual("user")
        m.stop()
        advanceUntilIdle()
    }

    @Test fun stop_all_stops_manual() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        m.startManual("abc")
        advanceUntilIdle()
        m.stop()
        advanceUntilIdle()
        assertTrue(m.manualState.value is ManualRecordState.Stopped)
    }

    // --- auto recording ---

    @Test fun auto_record_starts_and_stops_with_device_online_state() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        assertTrue(m.autoStates.value.containsKey("abc"))
        tracker.devices.value = emptyList()   // 设备下线 → stop recorder → flush file
        advanceUntilIdle()
        assertTrue(!m.autoStates.value.containsKey("abc"))
        val auto = dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }
        assertEquals(1, auto?.size)
        assertTrue(Files.readString(auto[0].toPath()).contains(line1))
        m.stop()
        advanceUntilIdle()
    }

    @Test fun auto_filename_uses_brand_model_androidversion() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        runner.whenArgsContains(listOf("getprop"), AdbProcessResult(0, getpropOut("TCL", "618D", "6.0"), ""))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("192.168.1.5:5555", DeviceStatus.ONLINE))
        advanceUntilIdle()
        assertTrue(m.autoStates.value.containsKey("192.168.1.5:5555"))
        val auto = dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("TCL_618D_Android6.0_") }
        assertEquals(1, auto?.size)
        m.stop()
        advanceUntilIdle()
    }

    @Test fun auto_record_disabled_setting_starts_nothing() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        settings.update { it.copy(logcatAutoRecord = false) }
        val m = LogcatRecordingManager(cmd, tracker, settings, noHistory(dir), NoopLogger, this, dir, io = Dispatchers.Unconfined)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        assertEquals(0, dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }?.size ?: 0)
        // 运行时打开开关 → 立即补启（spec §3.2）
        settings.update { it.copy(logcatAutoRecord = true) }
        advanceUntilIdle()
        assertEquals(1, dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }?.size ?: 0)
        m.stop()
        advanceUntilIdle()
    }

    @Test fun manual_recording_stops_when_device_disconnects() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        m.startManual("abc")
        advanceUntilIdle()
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.OFFLINE))
        advanceUntilIdle()
        val st = m.manualState.value
        assertTrue(st is ManualRecordState.Stopped)
        assertEquals("device_disconnected", st.reason)
        m.stop()
        advanceUntilIdle()
    }

    @Test fun rotated_auto_recorder_is_replaced_with_a_new_file() = runTest {
        val runner = FakeAdbProcessRunner()
        // once：第一个 recorder 收 2 行后 EOF；后续 recorder 拿到空流（streamLines 默认空）不轮转 → 稳定
        runner.setStreamLinesOnce(listOf(line1, line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        // line1 ≈ 40 chars：阈值取 1 行字节数 → 写完第 1 行即轮转
        val m = LogcatRecordingManager(cmd, tracker, settings, noHistory(dir), NoopLogger, this, dir,
            io = Dispatchers.Unconfined, rotationBytes = line1.length + 1L)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        // recorder1 轮转收尾（1 行），manager 续开 recorder2（空流、存活）→ auto_ 文件共 2 个
        val autoFiles = dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }
        assertEquals(2, autoFiles?.size)
        val lineCounts = autoFiles!!.map { Files.readString(it.toPath()).lines().count { l -> l.isNotBlank() } }
        assertEquals(1, lineCounts.count { it == 1 })
        m.stop()
        advanceUntilIdle()
    }

    // --- latest recording file (常驻定位按钮的数据源) ---

    @Test fun latestRecordingFile_returns_most_recent_log_ignoring_other_files() = runTest {
        val runner = FakeAdbProcessRunner()   // tracker 空 → 无 auto 文件干扰
        val dir = Files.createTempDirectory("adbgui-rec")
        Files.createDirectories(dir.resolve("manual"))
        val older = dir.resolve("manual").resolve("a.log"); older.writeText("x")
        val newer = dir.resolve("manual").resolve("b.log"); newer.writeText("x")
        dir.resolve("notes.txt").writeText("x")
        val now = System.currentTimeMillis()
        older.toFile().setLastModified(now - 60_000L)
        newer.toFile().setLastModified(now)
        val m = manager(runner, this, dir)
        advanceUntilIdle()
        assertEquals(newer, m.latestRecordingFile())
        m.stop()
        advanceUntilIdle()
    }

    @Test fun latestRecordingFile_null_when_no_recordings() = runTest {
        val dir = Files.createTempDirectory("adbgui-rec")
        val m = manager(FakeAdbProcessRunner(), this, dir)
        advanceUntilIdle()
        assertNull(m.latestRecordingFile())
        m.stop()
        advanceUntilIdle()
    }

    // --- file watchdog：活动会话的文件被外部删除 → 原地重启换新文件 ---

    @Test fun restartRecordersWithMissingFiles_restarts_auto_session() = runTest {
        // exists 注入 false 模拟"文件被删"（Windows 下打开中的文件真实删不掉）
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        val before = dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }
        assertEquals(1, before?.size)
        m.restartRecordersWithMissingFiles(exists = { _ -> false })
        advanceUntilIdle()
        assertTrue(m.autoStates.value.containsKey("abc"), "auto 会话应重启而非消失")
        val after = dir.resolve("auto").toFile().listFiles { f -> f.name.startsWith("abc_") }
        assertEquals(2, after?.size, "应换新文件重开（旧文件保留、新文件生成）")
        m.stop()
        advanceUntilIdle()
    }

    @Test fun restartRecordersWithMissingFiles_restarts_manual_without_stopped_state() = runTest {
        // 手动会话重启不得产生 Stopped 状态——否则 UI 的"停止后自动弹定位"会误触发
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir = Files.createTempDirectory("adbgui-rec")
        val tracker = FakeTracker()
        val m = manager(runner, this, dir, tracker)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        m.startManual("abc")
        advanceUntilIdle()
        assertTrue(m.manualState.value is ManualRecordState.Recording)
        val oldFile = (m.manualState.value as ManualRecordState.Recording).file
        m.restartRecordersWithMissingFiles(exists = { _ -> false })
        advanceUntilIdle()
        val st = m.manualState.value
        assertTrue(st is ManualRecordState.Recording, "watchdog 重启应保持 Recording，got: $st")
        assertTrue(st.file != oldFile, "应换新文件")
        m.stopManual("user")
        m.stop()
        advanceUntilIdle()
    }

    // --- startup cleanup ---

    @Test fun startup_cleanup_deletes_old_auto_but_keeps_manual() = runTest {
        val runner = FakeAdbProcessRunner()
        val dir = Files.createTempDirectory("adbgui-rec")
        Files.createDirectories(dir.resolve("auto"))
        Files.createDirectories(dir.resolve("manual"))
        val oldAuto = dir.resolve("auto").resolve("old.log"); oldAuto.writeText("x")
        val oldManual = dir.resolve("manual").resolve("old.log"); oldManual.writeText("x")
        val now = System.currentTimeMillis()
        oldAuto.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        oldManual.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        settings.update { it.copy(logcatAutoRecord = false) }   // 别让 auto 录制添新文件
        val m = LogcatRecordingManager(cmd, tracker, settings, noHistory(dir), NoopLogger, this, dir,
            io = Dispatchers.Unconfined, clock = { now })
        advanceUntilIdle()
        assertTrue(!Files.exists(oldAuto))     // 8 天前 auto → 删
        assertTrue(Files.exists(oldManual))    // manual 永不自动删（spec §3.2）
        m.stop()
        advanceUntilIdle()
    }

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
        val m = LogcatRecordingManager(cmd, tracker, settings, noHistory(dir), NoopLogger, this, dir,
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

    // --- hot-switch recordings dir (spec D2) ---

    @Test fun changing_dir_migrates_files_and_restarts_auto_in_new_dir() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val dir1 = Files.createTempDirectory("adbgui-rec1")
        val dir2 = Files.createTempDirectory("adbgui-rec2")
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir1, io = Dispatchers.Unconfined)
        val m = LogcatRecordingManager(cmd, tracker, settings, noHistory(dir1), NoopLogger, this, dir1, io = Dispatchers.Unconfined)
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
        val m = LogcatRecordingManager(cmd, tracker, settings, noHistory(dir1), NoopLogger, this, dir1, io = Dispatchers.Unconfined)
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
        val m = LogcatRecordingManager(cmd, tracker, settings, noHistory(dir1), NoopLogger, this, dir1, io = Dispatchers.Unconfined)
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
}
