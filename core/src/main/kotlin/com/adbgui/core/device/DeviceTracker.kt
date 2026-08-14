package com.adbgui.core.device

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.adb.AdbProcessRunner
import com.adbgui.core.adb.AdbServerController
import com.adbgui.core.adb.AdbStream
import com.adbgui.core.adb.DevicesListParser
import com.adbgui.core.adb.TrackDevicesParser
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TrackerStatus { IDLE, RUNNING, RECONNECTING, FAILED }

class DeviceTracker(
    private val adb: suspend () -> AdbBinary,
    private val server: AdbServerController,
    private val runner: AdbProcessRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : IDeviceTracker {
    private val _devices = MutableStateFlow<List<DeviceSnapshot>>(emptyList())
    override val devices: StateFlow<List<DeviceSnapshot>> = _devices.asStateFlow()

    private val _status = MutableStateFlow(TrackerStatus.IDLE)
    val status: StateFlow<TrackerStatus> = _status.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel(); job = null
        _status.value = TrackerStatus.IDLE
    }

    private suspend fun runLoop() {
        var backoff = 1000L
        var consecutiveFailures = 0
        while (true) {
            try {
                server.ensureStarted()
                val stream = runner.startStream(adb(), listOf("track-devices"), scope)
                _status.value = TrackerStatus.RUNNING
                stream.lines.collect { line ->
                    val snap = TrackDevicesParser.parseEvents(line) ?: return@collect
                    merge(snap)
                }
                // stream ended unexpectedly
                logger.warn("track-devices stream ended")
            } catch (t: Throwable) {
                logger.warn("track-devices error: ${t.message}")
            }
            consecutiveFailures++
            if (consecutiveFailures >= 3) {
                _status.value = TrackerStatus.FAILED
                logger.warn("track-devices failed 3x; falling back to polling adb devices")
                fallbackPoll()
                consecutiveFailures = 0
                backoff = 1000L
                continue
            }
            _status.value = TrackerStatus.RECONNECTING
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun fallbackPoll() {
        // poll a few times; on next loop iteration we retry the stream
        repeat(3) {
            try {
                val r = runner.run(adb(), listOf("devices"))
                val list = DevicesListParser.parse(r.stdout)
                _devices.value = list
            } catch (e: Exception) { logger.warn("poll error: ${e.message}") }
            delay(2000)
        }
    }

    private fun merge(snap: DeviceSnapshot) {
        val current = _devices.value.toMutableList()
        val idx = current.indexOfFirst { it.serial == snap.serial }
        if (idx >= 0) current[idx] = snap else current.add(snap)
        _devices.value = current
    }
}
