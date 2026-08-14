package com.adbgui.core.adb

import kotlinx.coroutines.test.runTest
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeAdbProcessRunnerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test
    fun returns_scripted_response_for_matching_args() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("devices"), AdbProcessResult(0, "List of devices attached\nabc device\n", ""))
        val result = runner.run(adb, listOf("devices"))
        assertEquals(0, result.exitCode)
        assert(result.stdout.contains("abc device"))
    }
}
