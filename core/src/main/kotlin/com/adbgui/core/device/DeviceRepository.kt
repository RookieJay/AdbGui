package com.adbgui.core.device

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.domain.ConnectResult
import com.adbgui.core.domain.DeviceProps
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.domain.DeviceView
import com.adbgui.core.domain.InstallResult
import com.adbgui.core.domain.PackageInfo
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

interface IDeviceTracker {
    val devices: StateFlow<List<DeviceSnapshot>>
}

class DeviceRepository(
    private val tracker: IDeviceTracker,
    private val history: DeviceHistoryStore,
    private val commands: CommandRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _devices = MutableStateFlow<List<DeviceView>>(emptyList())
    val devices: StateFlow<List<DeviceView>> = _devices.asStateFlow()

    private val collectorJob: Job

    init {
        runBlocking { recompute(tracker.devices.value) }   // populate _devices synchronously (history file is tiny)
        collectorJob = scope.launch {
            tracker.devices.collectLatest { recompute(it) }
        }
        scope.launch {
            // history changes are read on demand after mutations; recompute is called explicitly there
        }
    }

    fun stop() { collectorJob.cancel() }

    private suspend fun recompute(live: List<DeviceSnapshot>) {
        val hist = history.load().associateBy { it.serial }
        val merged = (live.map { it.serial } + hist.keys).distinct().map { serial ->
            val snap = live.firstOrNull { it.serial == serial }
            val h = hist[serial]
            DeviceView(
                serial = serial,
                status = snap?.status ?: DeviceStatus.OFFLINE,
                alias = h?.alias,
                type = h?.type,
                wirelessIp = h?.wirelessIp,
                wirelessPort = h?.wirelessPort,
                lastConnectedAt = h?.lastConnectedAt,
            )
        }
        _devices.value = merged
    }

    suspend fun connectWireless(ip: String, port: Int): ConnectResult {
        val r = commands.connect(ip, port)
        if (r.success) {
            history.upsert(serial = "$ip:$port", type = DeviceType.WIRELESS, wirelessIp = ip, wirelessPort = port)
            recompute(tracker.devices.value)
        }
        return r
    }

    suspend fun disconnect(target: String): Boolean = commands.disconnect(target)
    suspend fun listPackages(serial: String): List<PackageInfo> = commands.listPackages(serial)
    suspend fun install(serial: String, apkPath: String, reinstall: Boolean): InstallResult =
        commands.install(serial, apkPath, reinstall)
    suspend fun uninstall(serial: String, pkg: String): Boolean = commands.uninstall(serial, pkg)
    suspend fun clearData(serial: String, pkg: String): Boolean = commands.clearData(serial, pkg)
    suspend fun deviceProps(serial: String): DeviceProps = commands.deviceProps(serial)
    suspend fun deviceDetailReport(serial: String): String = commands.deviceDetailReport(serial)
    suspend fun screenshot(serial: String): ByteArray = commands.screenshot(serial)

    suspend fun setAlias(serial: String, alias: String?) {
        history.setAlias(serial, alias)
        recompute(tracker.devices.value)
    }

    suspend fun forgetDevice(serial: String) {
        history.remove(serial)
        recompute(tracker.devices.value)
    }
}
