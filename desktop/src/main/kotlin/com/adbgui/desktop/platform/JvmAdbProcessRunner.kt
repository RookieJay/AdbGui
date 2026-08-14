package com.adbgui.desktop.platform

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.AdbProcessRunner
import com.adbgui.core.adb.AdbStream
import com.adbgui.core.domain.AdbBinary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val finished = if (timeoutMs != null) withTimeoutOrNull(timeoutMs) { proc.waitFor() } else proc.waitFor()
        if (finished == null) { proc.destroyForcibly(); throw RuntimeException("adb timeout: ${args.joinToString(" ")}") }
        AdbProcessResult(proc.exitValue(), stdout, stderr)
    }

    override suspend fun runBinary(adb: AdbBinary, args: List<String>, timeoutMs: Long?): ByteArray = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(listOf(adb.path) + args).redirectErrorStream(false).start()
        val bytes = proc.inputStream.readBytes()
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
