package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeAdbStreamTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test fun startStream_emits_scripted_lines_and_stays_open_until_killed() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("a", "b", "c"))
        val stream = runner.startStream(adb, listOf("logcat"), this)
        val first = stream.lines.first()
        assertEquals("a", first)
        assertTrue(stream.isAlive)
        stream.kill()
    }

    @Test fun startStream_once_completes_after_lines_and_not_alive() = runTest {
        // `adb logcat -d` exits after dumping → real AdbStream.lines flow COMPLETES (readLine()
        // returns null) and isAlive turns false. The "once" mode models that so dumpLogcat's
        // collect returns naturally instead of hanging on an open channel.
        val runner = FakeAdbProcessRunner()
        runner.setStreamLinesOnce(listOf("a", "b", "c"))
        val stream = runner.startStream(adb, listOf("logcat", "-d"), this)
        val collected = mutableListOf<String>()
        stream.lines.collect { collected.add(it) }   // returns when flow completes
        assertEquals(listOf("a", "b", "c"), collected)
        assertFalse(stream.isAlive)
    }
}
