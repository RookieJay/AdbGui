@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.log.InMemoryLogger
import com.adbgui.core.log.LogLevel
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WirelessReconnectorTest {

    /** Count only adb connect calls (not getprop, devices, etc.) in the fake runner's log. */
    private fun connectAttempts(runner: FakeAdbProcessRunner): Int =
        runner.runs.count { args -> args.contains("connect") }

    private fun newRunner(): FakeAdbProcessRunner = FakeAdbProcessRunner()

    /** Build a CommandRunner wired to the fake process runner with a no-op adb server. */
    private fun newCmd(runner: FakeAdbProcessRunner, scope: kotlinx.coroutines.CoroutineScope) =
        CommandRunner({ AdbBinary("adb", AdbSource.PATH) }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter {})

    @Test
    fun retries_saved_endpoint_until_success_then_stops_retrying() = runTest {
        // Tracker starts empty; we'll update it via runSideEffect after the 3rd connect.
        val trackerFlow = MutableStateFlow(emptyList<DeviceSnapshot>())
        val tracker = object : IDeviceTracker {
            override val devices: StateFlow<List<DeviceSnapshot>> = trackerFlow
        }
        val history = DeviceHistoryStore(
            Files.createTempDirectory("reconn1"),
            clock = { testScheduler.currentTime },
            io = Dispatchers.Unconfined,
        )
        history.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)
        history.touchLastUsed("10.0.5.221:37751")

        val runner = newRunner()
        var connectCount = 0
        // First 2 connects fail; 3rd succeeds and makes the device appear online.
        runner.runSideEffect = { args ->
            if (args.contains("connect")) {
                connectCount++
                if (connectCount >= 3) {
                    runner.whenArgsContains(
                        listOf("connect"),
                        AdbProcessResult(0, "connected to 10.0.5.221:37751", ""),
                    )
                    trackerFlow.value = listOf(DeviceSnapshot("10.0.5.221:37751", DeviceStatus.ONLINE))
                }
            }
        }
        runner.whenArgsContains(listOf("connect"), AdbProcessResult(1, "", "failed"))
        // Script getprop so autoName/learnDeviceId don't log noise (not counted as connect).
        runner.whenArgsContains(
            listOf("getprop"),
            AdbProcessResult(0, "[ro.product.brand]: [Test]\n[ro.product.model]: [Dev]\n", ""),
        )

        val cmd = newCmd(runner, this)
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val log = InMemoryLogger(LogLevel.DEBUG, clock = { testScheduler.currentTime })
        val reconnector = WirelessReconnector(repo, history, log, this, clock = { testScheduler.currentTime })

        reconnector.start()
        // 3 attempts land at t=5s, 10s, 20s (backoff 5→10). 60s is well past the 3rd.
        advanceTimeBy(60_000)
        assertEquals(3, connectAttempts(runner), "should retry until success (2 fails + 1 success)")

        // Device is now online — advance more, no additional connect attempts.
        advanceTimeBy(120_000)
        assertEquals(3, connectAttempts(runner), "online device should not be retried")

        reconnector.stop()
        repo.stop()
    }

    @Test
    fun failure_backoff_caps_at_60s() = runTest {
        val trackerFlow = MutableStateFlow(emptyList<DeviceSnapshot>())
        val tracker = object : IDeviceTracker {
            override val devices: StateFlow<List<DeviceSnapshot>> = trackerFlow
        }
        val history = DeviceHistoryStore(
            Files.createTempDirectory("reconn2"),
            clock = { testScheduler.currentTime },
            io = Dispatchers.Unconfined,
        )
        history.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)
        history.touchLastUsed("10.0.5.221:37751")

        val runner = newRunner()
        // Connect always fails (default: exit 1, no matching script).
        val cmd = newCmd(runner, this)
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val log = InMemoryLogger(LogLevel.DEBUG, clock = { testScheduler.currentTime })
        val reconnector = WirelessReconnector(repo, history, log, this, clock = { testScheduler.currentTime })

        reconnector.start()
        // Advance 10 minutes. Backoff sequence: 5/10/20/40/60/60/60...
        // Attempt times (ms): 5k, 10k, 20k, 40k, 80k, 140k, 200k, 260k, 320k, 380k, 440k, 500k, 560k = 13
        // (next would be 620k > 600k).
        advanceTimeBy(600_000)
        assertEquals(13, connectAttempts(runner), "backoff should cap at 60s, yielding ~13 attempts in 600s")

        reconnector.stop()
        repo.stop()
    }

    @Test
    fun disabled_via_callback_makes_no_attempts() = runTest {
        val trackerFlow = MutableStateFlow(emptyList<DeviceSnapshot>())
        val tracker = object : IDeviceTracker {
            override val devices: StateFlow<List<DeviceSnapshot>> = trackerFlow
        }
        val history = DeviceHistoryStore(
            Files.createTempDirectory("reconn3"),
            clock = { testScheduler.currentTime },
            io = Dispatchers.Unconfined,
        )
        history.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)
        history.touchLastUsed("10.0.5.221:37751")

        val runner = newRunner()
        val cmd = newCmd(runner, this)
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val log = InMemoryLogger(LogLevel.DEBUG, clock = { testScheduler.currentTime })
        val reconnector = WirelessReconnector(
            repo, history, log, this,
            autoReconnectEnabled = { false },
            clock = { testScheduler.currentTime },
        )

        reconnector.start()
        advanceTimeBy(60_000)
        assertEquals(0, connectAttempts(runner), "disabled reconnector must not attempt any connect")

        reconnector.stop()
        repo.stop()
    }

    @Test
    fun entries_older_than_seven_days_are_skipped() = runTest {
        // Seed two entries with carefully controlled lastUsedAt values so we can verify the
        // 7-day staleness filter. We use a shared mutable clock for the history store writes
        // and an offset clock for the reconnector so both see consistent virtual time.
        val oneDay = 86_400_000L
        val baseline = 10_000_000L // arbitrary anchor; all times are relative
        var clockValue = baseline

        val trackerFlow = MutableStateFlow(emptyList<DeviceSnapshot>())
        val tracker = object : IDeviceTracker {
            override val devices: StateFlow<List<DeviceSnapshot>> = trackerFlow
        }
        val history = DeviceHistoryStore(
            Files.createTempDirectory("reconn4"),
            clock = { clockValue },
            io = Dispatchers.Unconfined,
        )
        // Entry A: lastUsedAt = baseline - 1 day (within 7-day window → recent).
        clockValue = baseline - oneDay
        history.upsert("10.0.5.1:5555", DeviceType.WIRELESS, "10.0.5.1", 5555)
        history.touchLastUsed("10.0.5.1:5555")

        // Entry B: lastUsedAt = baseline - 8 days (outside 7-day window → stale).
        clockValue = baseline - 8 * oneDay
        history.upsert("10.0.5.2:5555", DeviceType.WIRELESS, "10.0.5.2", 5555)
        history.touchLastUsed("10.0.5.2:5555")

        // Reset clock to baseline so the reconnector sees "now" = baseline + virtualTime.
        clockValue = baseline

        val runner = newRunner()
        // Connect always fails — we just want to observe WHICH entries are attempted.
        val cmd = newCmd(runner, this)
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this)
        val log = InMemoryLogger(LogLevel.DEBUG, clock = { testScheduler.currentTime })
        val reconnector = WirelessReconnector(
            repo, history, log, this,
            clock = { clockValue + testScheduler.currentTime },
        )

        reconnector.start()
        // Two ticks (10s) — enough for the recent entry to be attempted at least once.
        advanceTimeBy(10_000)

        val attemptedSerials = runner.runs
            .filter { args -> args.contains("connect") }
            .map { args -> args.first { it.contains(":") } }
        assertTrue(attemptedSerials.isNotEmpty(), "at least one attempt expected for the recent entry")
        assertTrue(attemptedSerials.all { it.startsWith("10.0.5.1:") },
            "only the recent entry should be attempted, got: $attemptedSerials")

        reconnector.stop()
        repo.stop()
    }
}
