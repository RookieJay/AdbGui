package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogcatControllerTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun controller(
        runner: FakeAdbProcessRunner,
        scope: kotlinx.coroutines.CoroutineScope,
        ringCap: Int = 5,
        publishIntervalMs: Long = 100,
        reResolveIntervalMs: Long = 10_000,
    ): LogcatController {
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        return LogcatController(
            cmd, NoopLogger, scope,
            ringCap = ringCap,
            publishIntervalMs = publishIntervalMs,
            reResolveIntervalMs = reResolveIntervalMs,
        )
    }

    private val line1 = "08-17 10:23:45.100  100  200 I Tag1: hello"
    private val line2 = "08-17 10:23:45.200  100  201 I Tag2: world"

    @Test fun start_collects_parsed_lines_into_state() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1, line2))
        val c = controller(runner, this)
        c.start("abc")
        advanceUntilIdle()
        assertEquals(2, c.lines.value.size)
        assertEquals("Tag1", c.lines.value[0].tag)
        c.stop()
    }

    @Test fun ring_caps_at_ringCap_dropping_oldest() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines((1..7).map { "08-17 10:23:45.00$it  100  200 I Tag$it: m$it" })
        val c = controller(runner, this)  // ringCap = 5
        c.start("abc")
        advanceUntilIdle()
        assertEquals(5, c.lines.value.size)
        assertEquals("Tag3", c.lines.value.first().tag)  // oldest 2 dropped
        c.stop()
    }

    @Test fun pause_drops_new_lines_resume_resumes() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1, line2))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        // Channel.UNLIMITED emits both lines synchronously before pause(), so both are
        // collected (size=2); this test asserts the pause/resume status toggle path instead.
        assertEquals(2, c.lines.value.size)
        assertEquals(LogcatStatus.RUNNING, c.status.value)
        c.pause()
        assertEquals(LogcatStatus.PAUSED, c.status.value)
        advanceUntilIdle()
        c.resume()
        assertEquals(LogcatStatus.RUNNING, c.status.value)
        c.stop()
    }

    @Test fun clear_empties_state_without_stopping() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        assertEquals(1, c.lines.value.size)
        c.clear()
        advanceUntilIdle()  // clear() routes through serialDispatcher (eventual) — let it complete
        assertEquals(0, c.lines.value.size)
        c.stop()
    }

    @Test fun clear_also_clears_device_logd_ring() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        runner.whenArgsContains(listOf("logcat", "-c"), AdbProcessResult(0, "", ""))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.clear()
        advanceUntilIdle()
        // The clear button must clear the on-device logd ring too — otherwise a fresh
        // logcat stream/record replays the entire hidden backlog (1M+ lines).
        assertTrue(runner.runs.any { it.containsAll(listOf("-s", "abc", "logcat", "-c")) })
        c.stop()
    }

    @Test fun clear_device_failure_still_clears_view_and_surfaces_error() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        // no script matches `logcat -c` → default exit 1 (device offline / clear failed)
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        assertEquals(1, c.lines.value.size)
        c.clear()
        advanceUntilIdle()
        assertEquals(0, c.lines.value.size)   // in-memory view still cleared (best-effort device clear)
        assertNotNull(c.error.value)          // failure surfaced inline, not swallowed
        c.stop()
    }

    @Test fun clear_without_device_skips_device_clear() = runTest {
        val runner = FakeAdbProcessRunner()
        val c = controller(runner, this)
        c.clear()
        advanceUntilIdle()
        assertTrue(runner.runs.isEmpty())     // no serial tracked → no adb command issued
    }

    @Test fun stop_resets_status_to_idle() = runTest {
        val runner = FakeAdbProcessRunner(); runner.setStreamLines(listOf(line1))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.stop()
        assertEquals(LogcatStatus.IDLE, c.status.value)
    }

    @Test fun setFilters_level_filters_lines() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I Tag: info",
            "08-17 10:23:45.200  100  201 W Tag: warn",
        ))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(levelSet = setOf(com.adbgui.core.domain.LogcatLevel.W)))
        advanceUntilIdle()
        assertEquals(1, c.lines.value.size)
        assertEquals("warn", c.lines.value[0].message)
        c.stop()
    }

    @Test fun setFilters_tag_text_pid_combine() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I Alpha: keep this one",
            "08-17 10:23:45.200  999  201 I Beta: drop by pid",
            "08-17 10:23:45.300  100  202 W Alpha: wrong level",
            "08-17 10:23:45.400  100  203 I Gamma: no text match",
        ))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(
            levelSet = com.adbgui.core.domain.LogcatLevel.entries.toSet(),
            tagInclude = "Alpha",
            text = "keep",
            pids = setOf(100),
        ))
        advanceUntilIdle()
        assertEquals(1, c.lines.value.size)
        assertEquals("keep this one", c.lines.value[0].message)
        c.stop()
    }

    @Test fun setFilters_text_regex_matches_pattern_case_sensitive() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I Tag: foo 123",
            "08-17 10:23:45.200  100  201 I Tag: bar abc",
            "08-17 10:23:45.300  100  202 I Tag: Foo 456",
        ))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(
            levelSet = com.adbgui.core.domain.LogcatLevel.entries.toSet(),
            text = "foo\\s\\d+",
            textIsRegex = true,
        ))
        advanceUntilIdle()
        assertEquals(1, c.lines.value.size)
        assertEquals("foo 123", c.lines.value[0].message)
        c.stop()
    }

    @Test fun setFilters_text_invalid_regex_falls_back_to_plain_contains() = runTest {
        // Bad pattern must not crash the stream; degrade to plain-contains (same text as a
        // literal substring) so filtering still works.
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I Tag: logcat said [unclosed bracket",
            "08-17 10:23:45.200  100  201 I Tag: other line",
        ))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(
            levelSet = com.adbgui.core.domain.LogcatLevel.entries.toSet(),
            text = "[unclosed",
            textIsRegex = true,
        ))
        advanceUntilIdle()
        assertEquals(1, c.lines.value.size)
        assertEquals("logcat said [unclosed bracket", c.lines.value[0].message)
        c.stop()
    }

    @Test fun setFilters_pids_filters_lines() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I Tag: keep",
            "08-17 10:23:45.200  999  201 I Tag: drop",
        ))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(pids = setOf(100)))
        advanceUntilIdle()
        assertEquals(1, c.lines.value.size)
        assertEquals("keep", c.lines.value[0].message)
        c.stop()
    }

    @Test fun setFilters_tagExcludes_filters_multiple_case_insensitive_blank_ignored() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I Noise: drop",
            "08-17 10:23:45.200  100  201 I NoiseBox: drop case-insensitive",
            "08-17 10:23:45.300  100  202 I Signal: keep",
        ))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(tagExcludes = listOf("noise", "  ")))
        advanceUntilIdle()
        assertEquals(1, c.lines.value.size)
        assertEquals("keep", c.lines.value[0].message)
        c.stop()
    }

    @Test fun export_returns_filtered_raw_lines_joined() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I Tag: one",
            "08-17 10:23:45.200  100  201 E Tag: two",
        ))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(levelSet = setOf(com.adbgui.core.domain.LogcatLevel.E)))
        advanceUntilIdle()
        val out = c.export()
        assert(out.contains("Tag: two"))
        assert(!out.contains("Tag: one"))
        c.stop()
    }

    @Test fun dumpLogcat_applies_filters_and_emits_only_matching_raw() = runTest {
        // -d full-buffer export: collect the one-shot stream, parse, apply current filters,
        // emit matching raw lines. Not bounded by ringCap.
        val runner = FakeAdbProcessRunner()
        runner.setStreamLinesOnce(listOf(
            "08-17 10:23:45.100  100  200 I Tag: info",
            "08-17 10:23:45.200  100  201 E Tag: err",
            "08-17 10:23:45.300  100  202 W Tag: warn",
        ))
        val c = controller(runner, this)
        val got = mutableListOf<String>()
        c.dumpLogcat("abc", LogcatFilters(levelSet = setOf(com.adbgui.core.domain.LogcatLevel.E))) { got += it }
        assertEquals(1, got.size)
        assertTrue(got[0].contains("Tag: err"))
    }

    @Test fun burst_of_many_lines_publishes_throttled_not_per_line() = runTest {
        // 2000 行一次性灌入（模型：logcat 连接时全量吐设备缓冲）。
        // 今天每行赋值一次 _lines → 2001 次 emission；节流后应 ≤ 4 次。
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines((1..2000).map { "08-17 10:23:45.100  100  200 I Tag$it: m$it" })
        val c = controller(runner, this, ringCap = 50000)
        var emissions = 0
        // Unconfined collector: a StateFlow collector on the test scheduler is CONFLATED, so a
        // per-line `_lines.value = ...` inside one un-yielding producer loop is never observed
        // (measured: 2 emissions for 2000 lines under the old per-line publish). Resuming
        // inline makes every publish observable — i.e. this counts publish OPERATIONS, which is
        // exactly what the throttle bounds.
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Unconfined) { c.lines.collect { emissions++ } }
        c.start("abc")
        advanceUntilIdle()
        assertEquals(2000, c.lines.value.size, "all lines must still reach the state")
        assertEquals("Tag2000", c.lines.value.last().tag)
        assertTrue(emissions <= 4, "expected throttled publishes for a 2000-line burst, got $emissions")
        c.stop(); collectJob.cancel()
    }

    @Test fun sustained_stream_keeps_publishing_not_starved() = runTest {
        // 每 30ms 来一行（快于 100ms 节流间隔）。若实现成防抖(collectLatest + delay)，
        // delay 会被每行取消 → 永不发布（饿死）。此测试锁死"节流而非防抖"。
        // 注意：这条在旧实现下也会通过（旧实现是每行发布）——它是新实现的行为守卫，不是红→绿。
        val runner = FakeAdbProcessRunner()
        val c = controller(runner, this, ringCap = 50)   // ringCap must exceed 20 or the final assert cannot hold
        var emissions = 0
        val collectJob = launch { c.lines.collect { emissions++ } }
        c.start("abc"); runCurrent()
        repeat(20) { i ->
            runner.emitStreamLine("08-17 10:23:45.100  100  200 I Tag$i: m$i")
            runCurrent()
            advanceTimeBy(30)
        }
        assertTrue(emissions >= 3, "sustained stream must keep publishing, got $emissions")
        // 推进虚拟时间让最后一批也发布出去。注意 `advanceUntilIdle()` 在这里能正常返回：
        // `setStreamLines`/`emitStreamLine` 留下的是**未关闭**的 channel，runLoop 永久挂在该
        // `collect` 上、不会走到 backoff 的 `delay`，因此没有"永远排程中"的任务。
        advanceUntilIdle()
        assertEquals(20, c.lines.value.size)
        c.stop(); collectJob.cancel()
    }

    @Test fun ring_semantics_survive_batching() = runTest {
        // 7 行 / ringCap 5 → 最终只有最新 5 行，且顺序正确（批量发布不能破坏环形截断）。
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines((1..7).map { "08-17 10:23:45.00$it  100  200 I Tag$it: m$it" })
        val c = controller(runner, this)   // ringCap = 5
        var last: List<com.adbgui.core.domain.LogcatLine> = emptyList()
        val collectJob = launch { c.lines.collect { last = it } }
        c.start("abc")
        advanceUntilIdle()
        assertEquals(listOf("Tag3", "Tag4", "Tag5", "Tag6", "Tag7"), c.lines.value.map { it.tag })
        assertEquals(5, last.size, "last published snapshot must match the ring")
        c.stop(); collectJob.cancel()
    }

    @Test fun package_filter_resolves_pids_and_filters_lines() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I TagA: from app",
            "08-17 10:23:45.200  999  201 I TagB: other app",
        ))
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "100\n", ""))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(packageName = "com.foo"))
        runCurrent()   // setFilters 协程 + 解析循环首拍（立即解析一次）
        assertEquals(1, c.lines.value.size)
        assertEquals("from app", c.lines.value[0].message)
        c.setFilters(LogcatFilters()); runCurrent()   // 停循环，否则下面的收尾会挂
        c.stop()
    }

    @Test fun reResolve_merges_new_pids_keeping_old() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I T: old pid",
            "08-17 10:23:45.200  300  201 I T: new pid",
            "08-17 10:23:45.300  999  202 I T: other",
        ))
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "100\n", ""))
        val c = controller(runner, this, reResolveIntervalMs = 1000)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(packageName = "com.foo"))
        runCurrent()                       // 首拍：pids={100}
        // 模拟 app 重启：pidof 现在返回新 pid（Task 2 的同关键词替换规则）
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "300\n", ""))
        advanceTimeBy(1001); runCurrent()  // 第二拍
        assertEquals(2, c.lines.value.size)  // 旧 pid 100 保留 + 新 pid 300
        c.setFilters(LogcatFilters()); runCurrent(); c.stop()
    }

    @Test fun pidof_failure_keeps_old_pids_and_stream_alive() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I T: from app",
            "08-17 10:23:45.200  999  201 I T: other",
        ))
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "100\n", ""))
        val c = controller(runner, this, reResolveIntervalMs = 1000)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
        // 设备闪断：pidof 抛 AdbCommandException（runCmd 非零退码）
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(1, "", "error: device offline"))
        advanceTimeBy(1001); runCurrent()
        assertEquals(1, c.lines.value.size)          // 旧 pids 保留
        assertEquals(LogcatStatus.RUNNING, c.status.value)  // 流不死
        c.setFilters(LogcatFilters()); runCurrent(); c.stop()
    }

    @Test fun clearing_package_stops_resolve_loop() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: m"))
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "100\n", ""))
        val c = controller(runner, this, reResolveIntervalMs = 1000)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
        val pidofCallsBefore = runner.runs.count { args -> args.any { it.contains("pidof") } }
        c.setFilters(LogcatFilters(packageName = null)); runCurrent()   // 清包 → 停循环
        advanceTimeBy(10_001)  // 若循环还活着这里会 tick 10 次
        assertEquals(pidofCallsBefore, runner.runs.count { args -> args.any { it.contains("pidof") } })
        c.stop()
    }

    @Test fun device_switch_restarts_resolve_for_new_serial() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: m"))
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "100\n", ""))
        val c = controller(runner, this)
        c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
        c.start("def"); runCurrent()   // 换设备 → stop() + 循环重启，用新 serial 解析
        assertTrue(runner.runs.any { args -> args.joinToString(" ").contains("-s def shell pidof com.foo") })
        c.setFilters(LogcatFilters()); runCurrent(); c.stop()
    }

    @Test fun device_switch_clears_old_pids_for_package_filter() = runTest {
        // 重要规约修正（review round 1 / spec D2）：换设备必须清旧 pids，
        // 否则旧 pid 与新设备行的 pid 碰撞会让不相关的行漏过包过滤。
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: old device line"))
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "100\n", ""))
        val c = controller(runner, this, reResolveIntervalMs = 1000)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
        assertEquals(setOf(100), c.filters.value.pids)
        assertEquals(1, c.lines.value.size)

        // 换设备：新 serial 的 pidof 返回 pid=300；新流里有旧 pid=100 的碰撞行 + 新 pid=300 的正常行。
        runner.setStreamLines(listOf(
            "08-17 10:23:45.100  100  200 I T: colliding old pid",
            "08-17 10:23:45.200  300  201 I T: real new pid",
        ))
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "300\n", ""))
        c.start("def")
        advanceTimeBy(200); runCurrent()   // 让 resolveLoop 首拍 + publishLoop 节流都走完

        assertEquals(setOf(300), c.filters.value.pids, "old pids must be cleared on device switch")
        assertEquals(1, c.lines.value.size, "old-pid line must be filtered out")
        assertEquals("real new pid", c.lines.value[0].message)

        c.setFilters(LogcatFilters()); runCurrent(); c.stop()
    }

    @Test fun setFilters_with_same_package_keeps_resolved_pids() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines(listOf("08-17 10:23:45.100  100  200 I T: m"))
        runner.whenArgsContains(listOf("pidof", "com.foo"), com.adbgui.core.adb.AdbProcessResult(0, "100\n", ""))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        c.setFilters(LogcatFilters(packageName = "com.foo")); runCurrent()
        // 用户改了级别，但传了空 pids —— controller 必须保住已解析的 {100}
        c.setFilters(LogcatFilters(packageName = "com.foo", levelSet = setOf(com.adbgui.core.domain.LogcatLevel.I)))
        runCurrent()
        assertEquals(setOf(100), c.filters.value.pids)
        c.setFilters(LogcatFilters()); runCurrent(); c.stop()
    }

    @Test fun snapshotTags_counts_from_published_lines_desc_capped() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.setStreamLines((1..3).map { "08-17 10:23:45.10$it  100  200 I Tag1: m$it" } +
            listOf("08-17 10:23:45.400  100  201 I Tag2: m"))
        val c = controller(runner, this)
        c.start("abc"); advanceUntilIdle()
        assertEquals(listOf("Tag1" to 3, "Tag2" to 1), c.snapshotTags())
        c.stop()
    }

    @Test fun dumpLogcat_applies_pids_filter() = runTest {
        // spec 测试计划 #3：全量导出与实时视图共用 matches() 谓词——pids 过滤必须同样生效。
        val runner = FakeAdbProcessRunner()
        runner.setStreamLinesOnce(listOf(
            "08-17 10:23:45.100  100  200 I Tag: from app",
            "08-17 10:23:45.200  999  201 I Tag: other",
        ))
        val c = controller(runner, this)
        val got = mutableListOf<String>()
        c.dumpLogcat("abc", LogcatFilters(pids = setOf(100))) { got += it }
        assertEquals(1, got.size)
        assertTrue(got[0].contains("from app"))
    }
}
