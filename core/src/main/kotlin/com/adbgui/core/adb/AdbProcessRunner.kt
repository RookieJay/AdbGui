package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

data class AdbProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

interface AdbStream {
    val lines: Flow<String>
    fun kill()
    val isAlive: Boolean
}

interface AdbProcessRunner {
    suspend fun run(adb: AdbBinary, args: List<String>, timeoutMs: Long? = null): AdbProcessResult
    suspend fun runBinary(adb: AdbBinary, args: List<String>, timeoutMs: Long? = null): ByteArray
    fun startStream(adb: AdbBinary, args: List<String>, scope: CoroutineScope): AdbStream
}

class FakeAdbProcessRunner : AdbProcessRunner {
    private val scripts = mutableListOf<Rule>()
    private var default = AdbProcessResult(1, "", "no script matched")

    private data class Rule(val keywords: List<String>, val result: AdbProcessResult)

    fun whenArgsContains(keywords: List<String>, result: AdbProcessResult) {
        scripts.add(Rule(keywords, result))
    }

    fun setDefault(result: AdbProcessResult) { default = result }

    private var binaryResponse: ByteArray = ByteArray(0)

    fun setBinaryResponse(b: ByteArray) { binaryResponse = b }

    override suspend fun run(adb: AdbBinary, args: List<String>, timeoutMs: Long?): AdbProcessResult {
        return scripts.firstOrNull { r -> r.keywords.all { kw -> args.any { it.contains(kw) } } }?.result
            ?: default
    }

    override suspend fun runBinary(adb: AdbBinary, args: List<String>, timeoutMs: Long?): ByteArray = binaryResponse

    override fun startStream(adb: AdbBinary, args: List<String>, scope: CoroutineScope): AdbStream {
        // Streaming is exercised via DeviceTracker tests using a FakeAdbStream; default stub.
        throw UnsupportedOperationException("set startStreamStub in tracker tests")
    }
}
