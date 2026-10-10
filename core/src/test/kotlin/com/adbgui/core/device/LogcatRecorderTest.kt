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
        append: Boolean = false,
    ): LogcatRecorder {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})
        return LogcatRecorder(cmd, NoopLogger, scope, io = Dispatchers.Unconfined, file = file,
            rotationBytes = rotationBytes, append = append)
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
        assertTrue(text.contains("serial=abc"))
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
}
