package com.adbgui.desktop.platform

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.AdbProcessRunner
import com.adbgui.core.adb.AdbStream
import com.adbgui.core.domain.AdbBinary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class JvmAdbProcessRunner : AdbProcessRunner {
    override suspend fun run(adb: AdbBinary, args: List<String>, timeoutMs: Long?): AdbProcessResult = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(listOf(adb.path) + args).redirectErrorStream(false).start()
        // adb output is UTF-8 (Android is UTF-8); decode explicitly — the JVM default charset
        // is MS936/GBK on Chinese Windows, which mangles non-ASCII (box-drawing, CJK process
        // names) into "?". Matches startStream below, which already uses UTF_8.
        val stdout = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val stderr = proc.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val finished = if (timeoutMs != null) withTimeoutOrNull(timeoutMs) { proc.waitFor() } else proc.waitFor()
        if (finished == null) { proc.destroyForcibly(); throw RuntimeException("adb timeout: ${args.joinToString(" ")}") }
        AdbProcessResult(proc.exitValue(), stdout, stderr)
    }

    override suspend fun runBinary(adb: AdbBinary, args: List<String>, timeoutMs: Long?): ByteArray = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(listOf(adb.path) + args).redirectErrorStream(false).start()
        // Read on a child job so a timeout can cancel the wait and destroy the process;
        // blocking readBytes() otherwise hangs forever if adb never closes stdout.
        val readDeferred = async { proc.inputStream.readBytes() }
        val bytes: ByteArray? = if (timeoutMs != null) {
            withTimeoutOrNull(timeoutMs) { readDeferred.await() }
        } else {
            readDeferred.await()
        }
        if (bytes == null) {
            proc.destroyForcibly()
            throw RuntimeException("adb timeout: ${args.joinToString(" ")}")
        }
        proc.waitFor()
        bytes
    }

    override fun startStream(adb: AdbBinary, args: List<String>, scope: CoroutineScope): AdbStream {
        val proc = ProcessBuilder(listOf(adb.path) + args).redirectErrorStream(false).start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val lineFlow: Flow<String> = flow {
            while (true) {
                val line = reader.readLine() ?: break
                emit(line)
            }
        }
        return object : AdbStream {
            override val lines = lineFlow
            override fun kill() { proc.destroyForcibly() }
            override val isAlive get() = proc.isAlive
        }
    }
}
