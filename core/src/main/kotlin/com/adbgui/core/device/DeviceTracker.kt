package com.adbgui.core.device

import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.adb.AdbProcessRunner
import com.adbgui.core.adb.AdbServerController
import com.adbgui.core.adb.DevicesListParser
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
        var consecutiveFailures = 0
        while (true) {
            try {
                server.ensureStarted()
                val r = runner.run(adb(), listOf("devices"))
                val list = DevicesListParser.parse(r.stdout)
                _devices.value = list
                _status.value = TrackerStatus.RUNNING
                consecutiveFailures = 0
            } catch (t: Throwable) {
                consecutiveFailures++
                // A failed poll may mean the adb server died; drop the cached "started"
                // flag so the next loop re-runs start-server (self-heal within ~2s).
                server.invalidate()
                // Log the full throwable (not just t.message, which for NoClassDefFoundError
                // is only a class name and hides the real init cause). This is the primary
                // diagnostic for tracker failures — keep it WARN, not DEBUG.
                logger.warn("devices poll error: ${t.message}", t)
                if (consecutiveFailures >= 3) {
                    _status.value = TrackerStatus.FAILED
                    logger.warn("devices poll failed 3x; continuing with backoff")
                } else {
                    _status.value = TrackerStatus.RECONNECTING
                }
            }
            delay(2000)
        }
    }
}
