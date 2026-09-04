package com.adbgui.core.device

import com.adbgui.core.domain.DeviceGroupBy
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.domain.DeviceView

/**
 * A named, ordered bucket of devices. [key] is a stable machine-readable identifier the UI
 * localizes into a display label (e.g. "type_usb" → "USB"). Order of the returned list is the
 * group display order; [DeviceGroup.devices] are already MRU-sorted within the group.
 */
data class DeviceGroup(val key: String, val devices: List<DeviceView>)

/**
 * Pure, side-effect-free device-list organization (sorting + grouping). Lives in `:core` so it is
 * unit-testable without UI, and so the same logic could feed a future CLI/sorting elsewhere.
 *
 * Sort policy: most-recently-used first (by `lastUsedAt`), nulls last. Within a group, devices
 * are MRU-sorted. **Group order: online-first, then MRU.** A group containing any online device
 * sorts above all-offline groups — so the "currently connected" device's group always surfaces,
 * regardless of mode (this is what makes the connected device's subnet/type/tag group land on
 * top even when its `lastUsedAt` is stale or null, e.g. migrated from a pre-feature devices.json).
 * Among same-online-status groups, the group with the larger max `lastUsedAt` ranks first. Ties
 * (incl. all-null, fresh install) fall back to the per-mode stable base order: USB before
 * wireless, subnets numerically, tags alphabetical.
 */
object DeviceListOrganizer {
    /**
     * MRU sort: `lastUsedAt` descending, nulls last. Stable for equal timestamps (sortedWith is a
     * stable merge sort). Implemented as an explicit comparator because `compareByDescending` +
     * `reversed()` both mishandle null placement (descending reverses nulls to the front).
     */
    private val mruComparator = Comparator<DeviceView> { a, b ->
        val la = a.lastUsedAt
        val lb = b.lastUsedAt
        when {
            la == null && lb == null -> 0
            la == null -> 1    // a after b (nulls last)
            lb == null -> -1    // b after a
            else -> lb.compareTo(la)  // descending: larger first
        }
    }

    fun sortMru(devices: List<DeviceView>): List<DeviceView> =
        devices.sortedWith(mruComparator)

    fun groupBy(devices: List<DeviceView>, mode: DeviceGroupBy): List<DeviceGroup> {
        if (devices.isEmpty()) return emptyList()
        val buckets: List<Pair<String, List<DeviceView>>> = when (mode) {
            DeviceGroupBy.NONE -> return listOf(DeviceGroup("", sortMru(devices)))
            DeviceGroupBy.TYPE -> typeBuckets(devices)
            DeviceGroupBy.STATUS -> statusBuckets(devices)
            DeviceGroupBy.SUBNET -> subnetBuckets(devices)
            DeviceGroupBy.TAG -> tagBuckets(devices)
        }
        // Online-first, then MRU: a group with any online device sorts above all-offline groups
        // (so the currently-connected device's group always surfaces), then by max lastUsedAt
        // desc. sortedByDescending is stable, so ties (incl. all-null groups) keep the base bucket
        // order above as a tiebreak. Pair<Boolean, Long> is Comparable: Boolean first (true >
        // false → online first), then Long desc.
        return buckets
            .sortedWith(
                compareByDescending<Pair<String, List<DeviceView>>> { (_, devs) -> devs.any { it.isLive } }
                    .thenByDescending { (_, devs) -> devs.mapNotNull { it.lastUsedAt }.maxOrNull() ?: Long.MIN_VALUE }
            )
            .map { DeviceGroup(it.first, it.second) }
    }

    private fun typeBuckets(devices: List<DeviceView>): List<Pair<String, List<DeviceView>>> {
        val byType = sortMru(devices).groupBy { it.type }
        val out = mutableListOf<Pair<String, List<DeviceView>>>()
        byType[DeviceType.USB]?.let { out.add("type_usb" to it) }
        byType[DeviceType.WIRELESS]?.let { out.add("type_wireless" to it) }
        byType[null]?.let { out.add("type_unknown" to it) }
        return out
    }

    private fun statusBuckets(devices: List<DeviceView>): List<Pair<String, List<DeviceView>>> {
        val sorted = sortMru(devices)
        val online = sorted.filter { it.status == DeviceStatus.ONLINE }
        val offline = sorted.filter { it.status != DeviceStatus.ONLINE }
        val out = mutableListOf<Pair<String, List<DeviceView>>>()
        if (online.isNotEmpty()) out.add("status_online" to online)
        if (offline.isNotEmpty()) out.add("status_offline" to offline)
        return out
    }

    private fun subnetBuckets(devices: List<DeviceView>): List<Pair<String, List<DeviceView>>> {
        // /24 = first three octets of the wireless IP. USB / malformed / missing IP → "subnet_none"
        // bucket. Base order: subnets numerically ascending by octet tuple (10.x < 192.x), then
        // the none bucket — but MRU ordering is applied on top, so a group wins if its max
        // lastUsedAt is larger, regardless of this base order.
        val keyed = sortMru(devices).map { d -> d to subnetKey(d) }
        val none = keyed.filter { it.second == null }.map { it.first }
        val bySubnet = keyed.filter { it.second != null }
            .groupBy { it.second!! }
            .map { (subnet, list) -> subnet to list.map { it.first } }
        val sortedSubnets = bySubnet.sortedBy { (subnet, _) ->
            val parts = subnet.split(".").map { it.toIntOrNull() ?: 0 }
            ((parts.getOrNull(0) ?: 0).toLong() shl 16) or ((parts.getOrNull(1) ?: 0).toLong() shl 8) or (parts.getOrNull(2) ?: 0).toLong()
        }
        val out = sortedSubnets.toMutableList()
        if (none.isNotEmpty()) out.add("subnet_none" to none)
        return out
    }

    /** Returns the /24 key "a.b.c", or null for USB / missing / malformed IP. */
    private fun subnetKey(d: DeviceView): String? {
        val ip = d.wirelessIp ?: return null
        val parts = ip.split(".")
        if (parts.size < 3) return null
        if (parts[0].toIntOrNull() == null || parts[1].toIntOrNull() == null || parts[2].toIntOrNull() == null) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    private fun tagBuckets(devices: List<DeviceView>): List<Pair<String, List<DeviceView>>> {
        val sorted = sortMru(devices)
        val tagged = sorted.filter { !it.tag.isNullOrBlank() }
        val untagged = sorted.filter { it.tag.isNullOrBlank() }
        // Base order: case-insensitive alphabetical by tag; untagged bucket last. MRU ordering
        // is applied on top — so if an untagged device was used most recently, that bucket still
        // sorts first.
        val sortedTags = tagged.groupBy { it.tag!! }.entries.sortedBy { it.key.lowercase() }
        val out = sortedTags.map { it.key to it.value }.toMutableList()
        if (untagged.isNotEmpty()) out.add("tag_none" to untagged)
        return out
    }
}
