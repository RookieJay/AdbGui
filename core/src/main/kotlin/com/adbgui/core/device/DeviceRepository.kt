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
        runBlocking { recompute(tracker.devices.value) }
        collectorJob = scope.launch {
            tracker.devices.collectLatest { recompute(it) }
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
            val serial = "$ip:$port"
            history.upsert(serial = serial, type = DeviceType.WIRELESS, wirelessIp = ip, wirelessPort = port)
            recompute(tracker.devices.value)
            // Auto-name: fetch brand+model and set alias so the list shows a friendly name
            // instead of a bare serial. Only when the device has NO existing alias — never
            // overwrite a user-set name. getprop is best-effort; failures don't break connect.
            val existing = history.load().firstOrNull { it.serial == serial }?.alias
            if (existing.isNullOrBlank()) {
                scope.launch {
                    runCatching {
                        val props = commands.deviceProps(serial)
                        val name = "${props.brand} ${props.model}".trim()
                        if (name.isNotBlank() && name.lowercase() != "unknown unknown") {
                            // Re-check alias right before writing — a user may have renamed
                            // between the check above and the getprop round-trip completing.
                            val still = history.load().firstOrNull { it.serial == serial }?.alias
                            if (still.isNullOrBlank()) setAlias(serial, name)
                        }
                    }
                }
            }
        }
        return r
    }

    suspend fun pair(ip: String, port: Int, code: String): com.adbgui.core.domain.PairResult {
        return commands.pair(ip, port, code)
    }

    suspend fun disconnect(target: String): Boolean = commands.disconnect(target)
    suspend fun adbVersion(): String = commands.adbVersion()
    suspend fun runShellCmd(serial: String, cmd: String): String = commands.runShellCmd(serial, cmd)
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

    suspend fun reboot(serial: String, mode: com.adbgui.core.domain.RebootMode): String = commands.reboot(serial, mode)
    suspend fun root(serial: String): String = commands.root(serial)
    suspend fun remount(serial: String): String = commands.remount(serial)
    suspend fun inputKey(serial: String, keycode: Int) = commands.inputKey(serial, keycode)
    suspend fun inputText(serial: String, text: String) = commands.inputText(serial, text)
    suspend fun forceStop(serial: String, pkg: String): String = commands.forceStop(serial, pkg)
    suspend fun startApp(serial: String, pkg: String): String = commands.startApp(serial, pkg)
    suspend fun startAppActivity(serial: String, pkg: String, activity: String): String = commands.startAppActivity(serial, pkg, activity)
    suspend fun sendBroadcast(serial: String, action: String, uri: String?, extras: List<com.adbgui.core.domain.Extra>): String = commands.sendBroadcast(serial, action, uri, extras)
    suspend fun queryProvider(serial: String, uri: String, where: String?): String = commands.queryProvider(serial, uri, where)
    suspend fun ls(serial: String, path: String): String = commands.ls(serial, path)
    suspend fun checkSymlinkDirs(serial: String, paths: List<String>): List<Boolean> = commands.checkSymlinkDirs(serial, paths)
    suspend fun push(serial: String, localPath: String, devicePath: String) = commands.push(serial, localPath, devicePath)
    suspend fun pull(serial: String, devicePath: String, localPath: String) = commands.pull(serial, devicePath, localPath)
}
