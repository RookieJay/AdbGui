package com.adbgui.core.adb

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
