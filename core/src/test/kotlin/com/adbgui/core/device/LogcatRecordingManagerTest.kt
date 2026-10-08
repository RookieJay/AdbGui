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
        val auto = dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }
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
        val auto = dir.toFile().listFiles { f -> f.name.startsWith("auto_TCL_618D_Android6.0_") }
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
        val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir, io = Dispatchers.Unconfined)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        assertEquals(0, dir.toFile().listFiles { f -> f.name.startsWith("auto_") }?.size)
        // 运行时打开开关 → 立即补启（spec §3.2）
        settings.update { it.copy(logcatAutoRecord = true) }
        advanceUntilIdle()
        assertEquals(1, dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }?.size)
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
        val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir,
            io = Dispatchers.Unconfined, rotationBytes = line1.length + 1L)
        tracker.devices.value = listOf(DeviceSnapshot("abc", DeviceStatus.ONLINE))
        advanceUntilIdle()
        // recorder1 轮转收尾（1 行），manager 续开 recorder2（空流、存活）→ auto_ 文件共 2 个
        val autoFiles = dir.toFile().listFiles { f -> f.name.startsWith("auto_abc_") }
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
        val older = dir.resolve("a.log"); older.writeText("x")
        val newer = dir.resolve("b.log"); newer.writeText("x")
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
        val before = dir.toFile().listFiles { f -> f.name.startsWith("auto_") }
        assertEquals(1, before?.size)
        m.restartRecordersWithMissingFiles(exists = { _ -> false })
        advanceUntilIdle()
        assertTrue(m.autoStates.value.containsKey("abc"), "auto 会话应重启而非消失")
        val after = dir.toFile().listFiles { f -> f.name.startsWith("auto_") }
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
        val oldAuto = dir.resolve("auto_old.log"); oldAuto.writeText("x")
        val oldManual = dir.resolve("manual_old.log"); oldManual.writeText("x")
        val now = System.currentTimeMillis()
        oldAuto.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        oldManual.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        val tracker = FakeTracker()
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter {})
        val settings = SettingsStore(dir, io = Dispatchers.Unconfined)
        settings.update { it.copy(logcatAutoRecord = false) }   // 别让 auto 录制添新文件
        val m = LogcatRecordingManager(cmd, tracker, settings, NoopLogger, this, dir,
            io = Dispatchers.Unconfined, clock = { now })
        advanceUntilIdle()
        assertTrue(!Files.exists(oldAuto))     // 8 天前 auto → 删
        assertTrue(Files.exists(oldManual))    // manual 永不自动删（spec §3.2）
        m.stop()
        advanceUntilIdle()
    }
}
