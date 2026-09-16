package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

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

    /** Every `run` call's full argv list, in invocation order — for asserting a specific command
     *  was actually issued (not just that a script would match if it were). */
    val runs = mutableListOf<List<String>>()

    private var binaryResponse: ByteArray = ByteArray(0)

    fun setBinaryResponse(b: ByteArray) { binaryResponse = b }

    private var streamLines: List<String> = emptyList()

    fun setStreamLines(lines: List<String>) { streamLines = lines }

    /** Lines for the next `startStream` call, emitted then the channel CLOSED — models a
     *  one-shot `adb` subcommand that exits after output (e.g. `logcat -d`, which dumps the
     *  device ring buffer and exits: the real stream's flow completes on EOF, `collect`
     *  returns, `isAlive` is false). Contrast [setStreamLines], which leaves the channel
     *  open (live `logcat` stream that stays up until `kill()`). */
    private var streamLinesOnce: List<String>? = null

    fun setStreamLinesOnce(lines: List<String>) { streamLinesOnce = lines }

    /** The channel of the most recent `startStream` call, so tests can pace a live stream
     *  instead of dumping every line at once. `@Volatile`: written by the caller thread that
     *  invokes `startStream`, read by [emitStreamLine] from elsewhere. */
    @Volatile private var lastStreamChannel: Channel<String>? = null

    /** Append one line to the channel opened by the most recent `startStream` — lets a test
     *  "pace" the stream rather than flushing it in one shot. Used to verify the throttler still
     *  publishes periodically under a sustained flow (i.e. it is a fixed-interval throttle, not
     *  a debounce that a never-idle stream would starve). */
    fun emitStreamLine(line: String) { lastStreamChannel?.trySend(line) }

    override suspend fun run(adb: AdbBinary, args: List<String>, timeoutMs: Long?): AdbProcessResult {
        runs += args
        return scripts.firstOrNull { r -> r.keywords.all { kw -> args.any { it.contains(kw) } } }?.result
            ?: default
    }

    override suspend fun runBinary(adb: AdbBinary, args: List<String>, timeoutMs: Long?): ByteArray = binaryResponse

    override fun startStream(adb: AdbBinary, args: List<String>, scope: CoroutineScope): AdbStream {
        val ch = Channel<String>(Channel.UNLIMITED)
        lastStreamChannel = ch
        val once = streamLinesOnce
        if (once != null) {
            once.forEach { ch.trySend(it) }
            ch.close()   // model process-exit EOF: flow completes after the buffered lines
            streamLinesOnce = null
        } else {
            streamLines.forEach { ch.trySend(it) }
            // channel is left OPEN so the flow stays alive until kill() — basic collect tests don't trigger reconnect
        }
        return object : AdbStream {
            override val lines: Flow<String> = ch.receiveAsFlow()
            override fun kill() { ch.close() }
            override val isAlive: Boolean = !ch.isClosedForSend
        }
    }
}
