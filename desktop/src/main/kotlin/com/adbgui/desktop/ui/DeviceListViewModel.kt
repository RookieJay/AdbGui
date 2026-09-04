package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceGroup
import com.adbgui.core.device.DeviceListOrganizer
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.ConnectFailureReason
import com.adbgui.core.domain.ConnectResult
import com.adbgui.core.domain.DeviceGroupBy
import com.adbgui.core.domain.DeviceView
import com.adbgui.core.domain.PairResult
import com.adbgui.core.settings.Settings
import com.adbgui.desktop.ui.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One renderable row in the device list. LazyColumn flattens groups into a stream of these so
 * group headers and device rows share a single scroll + scrollbar (and MRU order is preserved
 * top-to-bottom within each group).
 */
sealed class DeviceListItem {
    data class Header(val key: String, val count: Int) : DeviceListItem()
    data class Device(val view: DeviceView) : DeviceListItem()
}

class DeviceListViewModel(
    private val repo: DeviceRepository,
    private val scope: CoroutineScope,
    settings: StateFlow<Settings>,
) {
    /**
     * Flat list of renderable items (group headers + device rows), already MRU-sorted within each
     * group and group-ordered by [DeviceListOrganizer]. When groupBy == NONE there are no headers,
     * just device rows. Combining repo.devices with settings means a settings change (e.g. picking
     * "group by subnet") re-organizes the list without the repo re-emitting.
     *
     * Cold [Flow] (not [StateFlow]/stateIn) so constructing the VM does NOT launch a sharing
     * coroutine in the injected scope — that coroutine would never settle under `runTest`
     * ([UncompletedCoroutinesError]). The UI collects with an initial value via [collectAsState].
     */
    val items: Flow<List<DeviceListItem>> =
        combine(repo.devices, settings) { devices, s ->
            if (s.deviceGroupBy == DeviceGroupBy.NONE) {
                DeviceListOrganizer.sortMru(devices).map { DeviceListItem.Device(it) }
            } else {
                DeviceListOrganizer.groupBy(devices, s.deviceGroupBy).flatMap { g ->
                    listOf(DeviceListItem.Header(g.key, g.devices.size)) + g.devices.map { DeviceListItem.Device(it) }
                }
            }
        }

    /** The raw device list (still useful for callers that need devices without group headers). */
    val devices: StateFlow<List<DeviceView>> = repo.devices
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // One-shot "close the connect dialog" signal. Emitted from the VM's background scope on
    // successful connect and collected in ConnectDialog's LaunchedEffect (which runs on the
    // Compose UI thread). Routing dismissal through a flow — the same pattern _busy/_error
    // already use — avoids mutating plain Compose `mutableStateOf` from a background thread,
    // which does not reliably trigger recomposition. (The VM scope is Dispatchers.Default.)
    private val _dismissConnect = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dismissConnect: SharedFlow<Unit> = _dismissConnect.asSharedFlow()

    fun connect(ip: String, port: Int, onResult: (ConnectResult) -> Unit = {}) {
        scope.launch {
            _error.value = null
            _busy.value = true
            try {
                val r = repo.connectWireless(ip, port)
                if (r.success) _dismissConnect.tryEmit(Unit)
                else _error.value = formatConnectError(r, ip, port)
                onResult(r)
            } finally { _busy.value = false }
        }
    }

    fun disconnect(target: String) { scope.launch { repo.disconnect(target) } }

    fun reconnect(ip: String, port: Int) {
        scope.launch {
            _busy.value = true
            try {
                val r = repo.connectWireless(ip, port)
                if (!r.success) _error.value = formatConnectError(r, ip, port)
            } finally { _busy.value = false }
        }
    }

    /**
     * Port-stale / unreachable failures get an actionable hint (the wireless-debugging port
     * randomizes on reboot/re-enable, so a stored ip:port is often just stale — not a real
     * error). Raw adb text is preserved in the hint so nothing is silently swallowed. The
     * target ip:port is prefixed so the user can tell WHICH device failed when several are
     * tried in a row.
     */
    private fun formatConnectError(r: ConnectResult, ip: String, port: Int): String {
        val prefix = "$ip:$port\n"
        val body = if (r.reason == ConnectFailureReason.PORT_STALE || r.reason == ConnectFailureReason.UNREACHABLE) {
            Strings.t("wireless_connect_hint_unreachable").format(r.message)
        } else {
            r.message
        }
        return prefix + body
    }

    fun setAlias(serial: String, alias: String?) { scope.launch { repo.setAlias(serial, alias) } }
    fun setTag(serial: String, tag: String?) { scope.launch { repo.setTag(serial, tag) } }
    fun touchLastUsed(serial: String) { scope.launch { repo.touchLastUsed(serial) } }
    fun forget(serial: String) { scope.launch { repo.forgetDevice(serial) } }
    fun clearError() { _error.value = null }

    fun pair(ip: String, port: Int, code: String, onResult: (PairResult) -> Unit = {}) {
        scope.launch {
            _error.value = null
            _busy.value = true
            try {
                val pr = repo.pair(ip, port, code)
                // adb pair only registers the key. The pairing port is single-use and closes
                // right after pairing succeeds; connect must use the *connect* port shown on
                // the device's main "Wireless debugging" screen, which may differ. So the
                // UI drives a second connect step with a user-entered connect port — do NOT
                // auto-connect here (it would hit the now-closed pairing port and fail with
                // "protocol fault (couldn't read status message)").
                if (!pr.success) _error.value = pr.message
                onResult(pr)
            } finally { _busy.value = false }
        }
    }
}
