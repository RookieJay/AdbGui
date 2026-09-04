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
 * are MRU-sorted; group order is a stable, per-mode sensible default (online before offline, USB
 * before wireless, subnets numerically ascending with USB/no-IP last, tags alphabetical with
 * untagged last).
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
        return when (mode) {
            DeviceGroupBy.NONE -> listOf(DeviceGroup("", sortMru(devices)))
            DeviceGroupBy.TYPE -> groupByType(devices)
            DeviceGroupBy.STATUS -> groupByStatus(devices)
            DeviceGroupBy.SUBNET -> groupBySubnet(devices)
            DeviceGroupBy.TAG -> groupByTag(devices)
        }
    }

    private fun groupByType(devices: List<DeviceView>): List<DeviceGroup> {
        val byType = sortMru(devices).groupBy { it.type }
        val buckets = linkedMapOf<String, List<DeviceView>>()
        byType[DeviceType.USB]?.let { buckets["type_usb"] = it }
        byType[DeviceType.WIRELESS]?.let { buckets["type_wireless"] = it }
        byType[null]?.let { buckets["type_unknown"] = it }
        return buckets.map { DeviceGroup(it.key, it.value) }
    }

    private fun groupByStatus(devices: List<DeviceView>): List<DeviceGroup> {
        val sorted = sortMru(devices)
        val online = sorted.filter { it.status == DeviceStatus.ONLINE }
        val offline = sorted.filter { it.status != DeviceStatus.ONLINE }
        val buckets = linkedMapOf<String, List<DeviceView>>()
        if (online.isNotEmpty()) buckets["status_online"] = online
        if (offline.isNotEmpty()) buckets["status_offline"] = offline
        return buckets.map { DeviceGroup(it.key, it.value) }
    }

    private fun groupBySubnet(devices: List<DeviceView>): List<DeviceGroup> {
        // /24 = first three octets of the wireless IP. USB / malformed / missing IP → "subnet_none"
        // bucket, ordered last. Numeric ascending by octet tuple so 10.x < 192.x.
        val keyed = sortMru(devices).map { d -> d to subnetKey(d) }
        val none = keyed.filter { it.second == null }.map { it.first }
        val bySubnet = keyed.filter { it.second != null }
            .groupBy { it.second!! }
            .map { (subnet, list) -> subnet to list.map { it.first } }
        // Sort subnet keys numerically by octet tuple.
        val sortedSubnets = bySubnet.sortedBy { (subnet, _) ->
            val parts = subnet.split(".").map { it.toIntOrNull() ?: 0 }
            // Pack into a single long for stable numeric ordering (3 octets fit easily).
            ((parts.getOrNull(0) ?: 0).toLong() shl 16) or ((parts.getOrNull(1) ?: 0).toLong() shl 8) or (parts.getOrNull(2) ?: 0).toLong()
        }
        val out = sortedSubnets.map { DeviceGroup(it.first, it.second) }
        return if (none.isNotEmpty()) out + DeviceGroup("subnet_none", none) else out
    }

    /** Returns the /24 key "a.b.c", or null for USB / missing / malformed IP. */
    private fun subnetKey(d: DeviceView): String? {
        val ip = d.wirelessIp ?: return null
        val parts = ip.split(".")
        if (parts.size < 3) return null
        // Require all three leading octets be numeric; otherwise treat as no-IP.
        if (parts[0].toIntOrNull() == null || parts[1].toIntOrNull() == null || parts[2].toIntOrNull() == null) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    private fun groupByTag(devices: List<DeviceView>): List<DeviceGroup> {
        val sorted = sortMru(devices)
        val tagged = sorted.filter { !it.tag.isNullOrBlank() }
        val untagged = sorted.filter { it.tag.isNullOrBlank() }
        val byTag = tagged.groupBy { it.tag!! }
        // Case-insensitive alphabetical, but preserve original case in the key.
        val sortedTags = byTag.entries.sortedBy { it.key.lowercase() }
        val out = sortedTags.map { DeviceGroup(it.key, it.value) }
        return if (untagged.isNotEmpty()) out + DeviceGroup("tag_none", untagged) else out
    }
}
