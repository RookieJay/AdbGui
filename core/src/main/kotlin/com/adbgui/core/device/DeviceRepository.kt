package com.adbgui.core.device

import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.domain.ConnectResult
import com.adbgui.core.domain.DeviceProps
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.domain.DeviceView
import com.adbgui.core.domain.InstallFlags
import com.adbgui.core.domain.InstallResult
import com.adbgui.core.domain.MdnsService
import com.adbgui.core.domain.PackageInfo
import com.adbgui.core.log.Logger
import com.adbgui.core.mdns.MdnsScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface IDeviceTracker {
    val devices: StateFlow<List<DeviceSnapshot>>
}

class DeviceRepository(
    private val tracker: IDeviceTracker,
    private val history: DeviceHistoryStore,
    private val commands: CommandRunner,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val mdns: MdnsScanner? = null,
    // Gate for the mDNS auto-connect path — wired to the "无线设备自动重连" setting in
    // CompositionRoot (discovery itself is NOT gated, only auto-connecting).
    private val autoConnectEnabled: () -> Boolean = { true },
) {
    private val _devices = MutableStateFlow<List<DeviceView>>(emptyList())
    val devices: StateFlow<List<DeviceView>> = _devices.asStateFlow()

    private val _discovered = MutableStateFlow<List<MdnsService>>(emptyList())
    val discovered: StateFlow<List<MdnsService>> = _discovered.asStateFlow()

    private val collectorJob: Job
    private val mdnsCollectorJob: Job?

    // Auto-name coroutines run here, NOT on [scope]: naming is background work that
    // outlives individual recomputes (self-scheduled retries), and it must be stoppable
    // with [stop] without being a child of [scope] (runTest fails tests whose scope
    // still has active children at test end). Dispatcher inherited from [scope].
    private val namingJob = SupervisorJob()
    private val namingScope = CoroutineScope(scope.coroutineContext + namingJob)

    // Auto-name bookkeeping. Cross-thread mutable — guarded by mutex.
    // - inFlight: dedup concurrent attempts (recompute and connectWireless can race).
    // - attempts: bounded retries per online epoch (see [autoName]).
    // - launchLive: the last live list we scheduled naming for — the collector's initial
    //   value is the SAME list init already recomputed, and an unchanged list means no new
    //   naming candidates, so don't launch a second identical round (StateFlow also
    //   conflates equal values, so this only guards the startup double-processing).
    private val namingMutex = Mutex()
    private val namingInFlight = mutableSetOf<String>()
    private val namingAttempts = mutableMapOf<String, Int>()
    private var namingLaunchLive: List<DeviceSnapshot>? = null

    // mDNS auto-connect dedup. [mdnsInFlight] is read/written from the scanner collector
    // coroutine AND the coroutines it launches — guarded by [mdnsMutex] (independent of
    // [namingMutex] which guards auto-name bookkeeping above).
    private val mdnsMutex = Mutex()
    private val mdnsInFlight = mutableSetOf<String>()

    // Wireless endpoints ("ip:port") the user explicitly disconnected from the UI.
    // A manual disconnect is an intent, not an outage: auto-connect paths
    // ([WirelessReconnector], mDNS auto-connect) must skip these endpoints until the
    // user manually reconnects ([connectWireless]) or the app restarts (in-memory only).
    // @Volatile per the cross-thread mutable-state rule — written from UI coroutines,
    // read from the reconnector loop and scanner collector coroutines.
    @Volatile private var userDisconnectedEndpoints = emptySet<String>()

    /** True when the user disconnected [endpoint] ("ip:port") and hasn't manually
     *  reconnected since — auto-connect paths must skip it. */
    fun isUserDisconnected(endpoint: String): Boolean = endpoint in userDisconnectedEndpoints

    init {
        runBlocking { recompute(tracker.devices.value) }
        collectorJob = scope.launch {
            tracker.devices.collectLatest {
                recompute(it)
            }
        }
        mdnsCollectorJob = mdns?.let { scanner ->
            scope.launch {
                scanner.services.collect { handleMdnsServices(it) }
            }
        }
    }

    fun stop() {
        collectorJob.cancel()
        mdnsCollectorJob?.cancel()
        namingJob.cancel()
    }

    private suspend fun recompute(live: List<DeviceSnapshot>) {
        val hist = history.load()
        val merged = DeviceMerge.merge(live, hist)
        _devices.value = merged
        val onlineSerials = merged.filter { it.isLive }.map { it.serial }.toSet()
        val launchNaming = namingMutex.withLock {
            // A device that dropped offline gets a fresh auto-name attempt budget on its
            // next online epoch (e.g. it was rebooted and getprop works now).
            namingAttempts.keys.retainAll(onlineSerials)
            val firstTime = live != namingLaunchLive
            if (firstTime) namingLaunchLive = live
            firstTime
        }
        // Auto-name any ONLINE device that has no alias yet — covers USB plug-in, devices
        // already connected when the app starts, and wireless connect (fast path there
        // calls autoName directly). Never overwrites an existing (user-set) alias.
        // NOTE: recompute only fires when the tracker list VALUE changes (StateFlow
        // conflates equal values), so retries are self-scheduled — see [autoName].
        if (launchNaming) {
            merged.filter { it.isLive && it.alias == null }.forEach { namingScope.launch { autoName(it.serial) } }
        }
    }

    /**
     * Handle a fresh snapshot of mDNS services (spec §4.5):
     *  - Publish all services (port > 0) to [discovered] for UI display.
     *  - For `_adb-tls-connect._tcp` services whose [MdnsService.deviceSerial] OR host IP
     *    hits a known history entry (deviceId or wirelessIp): auto-connect via
     *    [connectWireless]. Dedup with [mdnsInFlight] so the scanner's 10s re-broadcasts
     *    don't stack concurrent connects to the same endpoint.
     *  - `_adb._tcp` (legacy) services: published to [discovered] only, never auto-connect.
     *  - Unknown services (no history hit): published to [discovered] only.
     */
    private suspend fun handleMdnsServices(services: List<MdnsService>) {
        _discovered.value = services
        if (!autoConnectEnabled()) return
        val hist = history.load()
        val knownDeviceIds = hist.mapNotNull { it.deviceId }.toSet()
        val knownIps = hist.mapNotNull { it.wirelessIp }.toSet()
        for (svc in services) {
            if (svc.serviceType != MdnsScanner.SERVICE_TLS) continue
            val key = "${svc.host}:${svc.port}"
            val known = (svc.deviceSerial?.let { it in knownDeviceIds } == true) || svc.host in knownIps
            if (!known) continue
            // User manually disconnected this endpoint — respect that intent; discovery
            // (published to [discovered] above) is unaffected, only auto-connect skips.
            if (isUserDisconnected(key)) continue
            val inFlight = mdnsMutex.withLock {
                if (key in mdnsInFlight) true else { mdnsInFlight += key; false }
            }
            if (inFlight) continue
            scope.launch {
                try {
                    val r = connectWireless(svc.host, svc.port)
                    if (r.success) logger.info("mdns auto-connect: ${svc.instanceName} -> $key")
                    else logger.debug("mdns auto-connect failed for ${svc.instanceName} ($key): ${r.message}")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("mdns auto-connect threw for ${svc.instanceName} ($key): ${e.message}")
                } finally {
                    mdnsMutex.withLock { mdnsInFlight -= key }
                }
            }
        }
    }

    /**
     * Set the device's alias to "<brand> <model>" from getprop, unless it already has an
     * alias. Best-effort: failures log at WARN and self-schedule a retry (while the device
     * stays online), up to [AUTO_NAME_MAX_ATTEMPTS] per online epoch. (The 2026-09-26 bug:
     * a transient getprop failure right after `adb connect` was swallowed silently with no
     * retry — and the device list staying unchanged means no recompute would ever fire
     * again — leaving the device unnamed forever.)
     */
    private suspend fun autoName(serial: String) {
        namingMutex.withLock {
            if (serial in namingInFlight) return
            val attempts = (namingAttempts[serial] ?: 0) + 1
            namingAttempts[serial] = attempts
            if (attempts > AUTO_NAME_MAX_ATTEMPTS) {
                if (attempts == AUTO_NAME_MAX_ATTEMPTS + 1) {
                    logger.info("auto-name: giving up on $serial after $AUTO_NAME_MAX_ATTEMPTS attempts (budget resets when it reconnects)")
                }
                return
            }
            namingInFlight += serial
        }
        try {
            val existing = history.load().firstOrNull { it.serial == serial }?.alias
            if (existing.isNullOrBlank()) {
                val props = commands.deviceProps(serial)
                val name = "${props.brand} ${props.model}".trim()
                if (name.isNotBlank() && name.lowercase() != "unknown unknown") {
                    // Re-check alias right before writing — a user may have renamed during
                    // the getprop round-trip.
                    val still = history.load().firstOrNull { it.serial == serial }?.alias
                    if (still.isNullOrBlank()) {
                        setAlias(serial, name)
                        logger.info("auto-name: $serial -> \"$name\"")
                        // Lazy deviceId learning (spec §4.3): if this wireless device's
                        // history entry still has no deviceId (e.g. earlier connect's
                        // getprop ro.serialno failed), learn it now while we're already
                        // talking to the device.
                        if (serial.contains(':')) {
                            val entry = history.load().firstOrNull { it.serial == serial }
                            if (entry != null && entry.deviceId == null) {
                                learnDeviceId(serial)?.let { did ->
                                    // The entry may be a bare one born from setAlias (no
                                    // wirelessIp/port). Fall back to the ip:port serial so the
                                    // merged entry keeps an address (WirelessReconnector filters
                                    // on ip != null) and the same-IP stale merge can fire.
                                    val ip = entry.wirelessIp ?: serial.substringBefore(':')
                                    val port = entry.wirelessPort ?: serial.substringAfter(':').toIntOrNull()
                                    val removed = history.mergeOnConnect(did, serial, DeviceType.WIRELESS, ip, port)
                                    removed.forEach { logger.info("history: merged duplicate entry ${it.serial} into deviceId=$did (lazy learn)") }
                                    recompute(tracker.devices.value)
                                }
                            }
                        }
                    }
                } else {
                    logger.debug("auto-name: no usable brand/model for $serial, retrying in ${AUTO_NAME_RETRY_MS / 1000}s")
                    scheduleAutoNameRetry(serial)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("auto-name failed for $serial (will retry in ${AUTO_NAME_RETRY_MS / 1000}s): ${e.message}")
            scheduleAutoNameRetry(serial)
        } finally {
            namingMutex.withLock { namingInFlight -= serial }
        }
    }

    private fun isOnlineNow(serial: String): Boolean =
        tracker.devices.value.any { it.serial == serial && it.status == DeviceStatus.ONLINE }

    private fun scheduleAutoNameRetry(serial: String) {
        namingScope.launch {
            delay(AUTO_NAME_RETRY_MS)
            if (isOnlineNow(serial)) autoName(serial)
        }
    }

    companion object {
        private const val AUTO_NAME_RETRY_MS = 60_000L
        private const val AUTO_NAME_MAX_ATTEMPTS = 5
    }

    suspend fun connectWireless(ip: String, port: Int): ConnectResult {
        // A manual connect (re)asserts user intent — drop any disconnect suppression for
        // this endpoint so auto-reconnect resumes if the device drops again later.
        userDisconnectedEndpoints = userDisconnectedEndpoints - "$ip:$port"
        val r = commands.connect(ip, port)
        if (r.success) {
            val serial = "$ip:$port"
            val deviceId = learnDeviceId(serial)
            if (deviceId != null) {
                val removed = history.mergeOnConnect(deviceId, serial, DeviceType.WIRELESS, ip, port)
                removed.forEach { logger.info("history: merged duplicate entry ${it.serial} into deviceId=$deviceId") }
            } else {
                history.upsert(serial = serial, type = DeviceType.WIRELESS, wirelessIp = ip, wirelessPort = port)
            }
            // A successful connect counts as "use" for MRU sort.
            history.touchLastUsed(serial)
            recompute(tracker.devices.value)
            // Auto-name now (fast path — don't wait for the 2s tracker poll to report the
            // device online); the recompute path covers retries, USB, and app-start cases.
            namingScope.launch { autoName(serial) }
        }
        return r
    }

    /** `getprop ro.serialno` → stable cross-port deviceId. Returns null on any failure
     *  (unauthorized / offline race / empty / suspicious value) — never blocks the connect
     *  success path. Sanity-checks: non-empty, no whitespace (would break serial parsing),
     *  length ≤ 32 (real serials are 8–16 chars; anything longer is garbage). */
    private suspend fun learnDeviceId(serial: String): String? = try {
        val v = commands.runShellCmd(serial, "getprop ro.serialno").trim()
        if (v.isNotEmpty() && !v.contains(' ') && v.length <= 32) v else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("deviceId learn failed for $serial: ${e.message}")
        null
    }

    suspend fun pair(ip: String, port: Int, code: String): com.adbgui.core.domain.PairResult {
        return commands.pair(ip, port, code)
    }

    suspend fun disconnect(target: String): Boolean {
        val ok = commands.disconnect(target)
        // Wireless target ("ip:port") disconnected on purpose → suppress auto-reconnect
        // for that endpoint until the user manually reconnects. USB serials (no ':')
        // carry no wireless endpoint and can't be auto-connected anyway.
        if (ok && target.contains(':')) {
            userDisconnectedEndpoints = userDisconnectedEndpoints + target
            logger.info("user disconnected $target — auto-reconnect suppressed until manual reconnect")
        }
        return ok
    }
    suspend fun adbVersion(): String = commands.adbVersion()
    suspend fun runShellCmd(serial: String, cmd: String): String = commands.runShellCmd(serial, cmd)
    suspend fun listPackages(serial: String): List<PackageInfo> = commands.listPackages(serial)
    suspend fun dumpsysPackage(serial: String, pkg: String): com.adbgui.core.domain.DumpsysPackage =
        commands.dumpsysPackage(serial, pkg)

    suspend fun install(serial: String, paths: List<String>, flags: InstallFlags): InstallResult =
        commands.install(serial, paths, flags)
    suspend fun uninstall(serial: String, pkg: String): Boolean = commands.uninstall(serial, pkg)
    suspend fun clearData(serial: String, pkg: String): Boolean = commands.clearData(serial, pkg)
    suspend fun deviceProps(serial: String): DeviceProps = commands.deviceProps(serial)
    suspend fun deviceDetailReport(serial: String): String = commands.deviceDetailReport(serial)
    suspend fun screenshot(serial: String): ByteArray = commands.screenshot(serial)

    suspend fun setAlias(serial: String, alias: String?) {
        history.setAlias(serial, alias)
        recompute(tracker.devices.value)
    }

    /** Stamp "last used" (selection / connect) for MRU sorting. */
    suspend fun touchLastUsed(serial: String) {
        history.touchLastUsed(serial)
        recompute(tracker.devices.value)
    }

    /** Set a free-form grouping tag (null clears). */
    suspend fun setTag(serial: String, tag: String?) {
        history.setTag(serial, tag)
        recompute(tracker.devices.value)
    }

    /** Clear [tag] from every device bearing it, atomically. See [DeviceHistoryStore.clearTag]. */
    suspend fun clearTag(tag: String) {
        history.clearTag(tag)
        recompute(tracker.devices.value)
    }

    /** Rename [oldTag] to [newTag] on every device bearing it, atomically (merge when [newTag] exists). See [DeviceHistoryStore.renameTag]. */
    suspend fun renameTag(oldTag: String, newTag: String) {
        history.renameTag(oldTag, newTag)
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
    suspend fun startActivity(serial: String, action: String?, data: String?, component: String?, extras: List<com.adbgui.core.domain.Extra>): String = commands.startActivity(serial, action, data, component, extras)
    suspend fun sendBroadcast(serial: String, action: String, uri: String?, extras: List<com.adbgui.core.domain.Extra>): String = commands.sendBroadcast(serial, action, uri, extras)
    suspend fun queryProvider(serial: String, uri: String, where: String?): String = commands.queryProvider(serial, uri, where)
    suspend fun ls(serial: String, path: String): String = commands.ls(serial, path)
    suspend fun grant(serial: String, pkg: String, perm: String): String = commands.grant(serial, pkg, perm)
    suspend fun revoke(serial: String, pkg: String, perm: String): String = commands.revoke(serial, pkg, perm)
    suspend fun listNativeLibs(serial: String, dir: String): List<String> = commands.listNativeLibs(serial, dir)
    suspend fun checkSymlinkDirs(serial: String, paths: List<String>): List<Boolean> = commands.checkSymlinkDirs(serial, paths)
    suspend fun push(serial: String, localPath: String, devicePath: String) = commands.push(serial, localPath, devicePath)
    suspend fun pull(serial: String, devicePath: String, localPath: String) = commands.pull(serial, devicePath, localPath)
    suspend fun forward(serial: String, local: com.adbgui.core.domain.ForwardSpec, remote: com.adbgui.core.domain.ForwardSpec) =
        commands.forward(serial, local, remote)
    /** This device's forwards only — `adb forward --list` is host-wide, filtered here (R4). */
    suspend fun listForwards(serial: String): List<com.adbgui.core.domain.ForwardEntry> =
        commands.listForwardsRaw().filter { it.serial == serial }
    suspend fun removeForward(serial: String, local: com.adbgui.core.domain.ForwardSpec) =
        commands.removeForward(serial, local)
    suspend fun removeAllForwards(serial: String) = commands.removeAllForwards(serial)
    suspend fun bugreport(serial: String, destDir: String): com.adbgui.core.domain.BugreportResult =
        commands.bugreport(serial, destDir)
}
