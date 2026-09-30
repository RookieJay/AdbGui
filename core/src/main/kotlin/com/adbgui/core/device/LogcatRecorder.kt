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
import kotlinx.coroutines.flow.collect
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
            val flushJob = launch { flushLoop() }   // 子协程：finally 中取消
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
                flushJob.cancel()
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

    /** 断流退避重连循环。文件写入走 BufferedWriter（缓冲写，非阻塞 syscall）；
     *  delay 在父 context 以兼容 TestDispatcher 虚拟时间。
     *  流收集+行写入跑在 [io] 上，避免阻塞 Default 池（auto-record 每设备一条流，
     *  Default 池会被少量设备耗尽）。 */
    private suspend fun runLoop(serial: String) {
        var backoff = 1000L
        var exitLoop = false
        var firstAttempt = true
        while (!exitLoop) {
            try {
                withContext(io) {
                    val s = commands.streamLogcat(serial)
                    stream = s
                    // spec §3.5：重连标记每次实际重连写一次（不是在每次退避前）
                    if (!firstAttempt) {
                        val ts = java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                        )
                        writer?.write("--------- adbgui reconnect $ts serial=$serial\n")
                    }
                    firstAttempt = false
                    var done = false
                    s.lines.collect { line ->
                        if (done) return@collect
                        val w = writer
                        if (w == null) { done = true; s.kill(); return@collect }
                        w.write(line); w.write("\n")   // 显式 \n，不用 appendLine（避免 \r\n）
                        // 字节计数（UTF-8）：CJK 3 字节/字符，避免 UI/轮转阈值被低估
                        val bytes = _state.value.bytesWritten + line.toByteArray(Charsets.UTF_8).size.toLong() + 1L
                        _state.value = _state.value.copy(
                            lineCount = _state.value.lineCount + 1, bytesWritten = bytes
                        )
                        if (bytes >= rotationBytes) {
                            w.flush()
                            _state.value = _state.value.copy(rotated = true)
                            done = true
                            s.kill()   // 关通道 → flow 排空缓冲后结束 → collect 返回
                            return@collect
                        }
                    }
                    exitLoop = done   // rotation or writer nulled → exit loop
                }
                if (!exitLoop) logger.warn("logcat record stream ended for $serial (reconnecting)")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logger.warn("logcat record stream error for $serial: ${t.message}")
                _state.value = _state.value.copy(error = t.message)
            }
            if (!exitLoop) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** 每 1s flush：崩溃最多丢尾 1 秒（spec §7）。BufferedWriter 内部同步，与 write 并发安全。
     *  跑在 io 上：delay 用真实时间（避免干扰 TestDispatcher 虚拟时间）。 */
    private suspend fun flushLoop() {
        withContext(io) {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                runCatching { writer?.flush() }
            }
        }
    }

    companion object {
        const val ROTATION_BYTES: Long = 100L * 1024 * 1024
        private const val FLUSH_INTERVAL_MS = 1000L
    }
}
