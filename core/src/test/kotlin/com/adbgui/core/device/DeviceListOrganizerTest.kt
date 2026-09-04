package com.adbgui.core.device

import com.adbgui.core.domain.DeviceGroupBy
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import com.adbgui.core.domain.DeviceView
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceListOrganizerTest {
    private fun view(
        serial: String,
        lastUsedAt: Long? = null,
        type: DeviceType? = null,
        status: DeviceStatus = DeviceStatus.OFFLINE,
        wirelessIp: String? = null,
        tag: String? = null,
    ) = DeviceView(
        serial = serial,
        status = status,
        type = type,
        wirelessIp = wirelessIp,
        lastUsedAt = lastUsedAt,
        tag = tag,
    )

    @Test
    fun sortMru_orders_by_lastUsedAt_desc_nulls_last() {
        val a = view("a", lastUsedAt = 100L)
        val b = view("b", lastUsedAt = 300L)
        val c = view("c", lastUsedAt = 200L)
        val d = view("d", lastUsedAt = null)
        val out = DeviceListOrganizer.sortMru(listOf(a, b, c, d))
        assertEquals(listOf("b", "c", "a", "d"), out.map { it.serial })
    }

    @Test
    fun groupBy_NONE_returns_one_flat_group_sorted_by_mru() {
        val a = view("a", lastUsedAt = 100L)
        val b = view("b", lastUsedAt = 300L)
        val groups = DeviceListOrganizer.groupBy(listOf(a, b), DeviceGroupBy.NONE)
        assertEquals(1, groups.size)
        assertEquals(listOf("b", "a"), groups[0].devices.map { it.serial })
    }

    @Test
    fun groupBy_TYPE_orders_groups_by_mru_wireless_used_most_recently_first() {
        // wireless group's max lastUsedAt (999) > USB group's max (300) → wireless group first,
        // even though USB would be the "base" order. Within each group, MRU.
        val usb1 = view("u1", lastUsedAt = 100L, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val usb2 = view("u2", lastUsedAt = 300L, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val wl1 = view("w1", lastUsedAt = 50L, type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE, wirelessIp = "10.0.0.1")
        val wl2 = view("w2", lastUsedAt = 999L, type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE, wirelessIp = "10.0.0.2")
        val groups = DeviceListOrganizer.groupBy(listOf(wl2, usb1, wl1, usb2), DeviceGroupBy.TYPE)
        assertEquals(listOf("type_wireless", "type_usb"), groups.map { it.key })
        assertEquals(listOf("w2", "w1"), groups[0].devices.map { it.serial })
        assertEquals(listOf("u2", "u1"), groups[1].devices.map { it.serial })
    }

    @Test
    fun groupBy_TYPE_all_null_lastUsed_falls_back_to_base_order_usb_first() {
        // When no device has a lastUsedAt (fresh install, nothing used yet), groups keep the
        // stable base order (USB before wireless) — no MRU signal to rank by.
        val usb = view("u", lastUsedAt = null, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val wl = view("w", lastUsedAt = null, type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE, wirelessIp = "10.0.0.1")
        val groups = DeviceListOrganizer.groupBy(listOf(wl, usb), DeviceGroupBy.TYPE)
        assertEquals(listOf("type_usb", "type_wireless"), groups.map { it.key })
    }

    @Test
    fun groupBy_TYPE_unknown_type_bucket_participates_in_mru_order() {
        val usb = view("u", lastUsedAt = 5L, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val unk = view("k", lastUsedAt = 2L, type = null, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(unk, usb), DeviceGroupBy.TYPE)
        // usb max=5 > unk max=2 → USB first, unknown last.
        assertEquals(listOf("type_usb", "type_unknown"), groups.map { it.key })
    }

    @Test
    fun groupBy_STATUS_online_group_first_when_its_member_used_most_recently() {
        // online device used more recently than the offline one → online group first.
        val off1 = view("o1", lastUsedAt = 10L, status = DeviceStatus.OFFLINE)
        val on1 = view("n1", lastUsedAt = 200L, status = DeviceStatus.ONLINE)
        val on2 = view("n2", lastUsedAt = 500L, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(off1, on1, on2), DeviceGroupBy.STATUS)
        assertEquals(listOf("status_online", "status_offline"), groups.map { it.key })
        assertEquals(listOf("n2", "n1"), groups[0].devices.map { it.serial })
        assertEquals(listOf("o1"), groups[1].devices.map { it.serial })
    }

    @Test
    fun groupBy_STATUS_online_group_first_even_if_offline_used_more_recently() {
        // Online-first trumps MRU: even if the offline device was used more recently, the online
        // group sorts first — the "currently connected" device's group must surface.
        val off1 = view("o1", lastUsedAt = 999L, status = DeviceStatus.OFFLINE)
        val on1 = view("n1", lastUsedAt = 10L, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(off1, on1), DeviceGroupBy.STATUS)
        assertEquals(listOf("status_online", "status_offline"), groups.map { it.key })
    }

    @Test
    fun groupBy_SUBNET_online_group_first_even_if_offline_subnet_used_more_recently() {
        // Cross-mode: a subnet with an online device sorts above a subnet whose (offline) device
        // was used more recently. This is the fix for "connected device's group not on top".
        val off = view("o", lastUsedAt = 999L, wirelessIp = "10.0.0.5", type = DeviceType.WIRELESS, status = DeviceStatus.OFFLINE)
        val on = view("n", lastUsedAt = 1L, wirelessIp = "192.168.50.9", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(off, on), DeviceGroupBy.SUBNET)
        assertEquals(listOf("192.168.50", "10.0.0"), groups.map { it.key })
    }

    @Test
    fun groupBy_STATUS_treats_unauthorized_and_unknown_as_offline() {
        val unauth = view("ua", lastUsedAt = 1L, status = DeviceStatus.UNAUTHORIZED)
        val unknown = view("uk", lastUsedAt = 2L, status = DeviceStatus.UNKNOWN)
        val groups = DeviceListOrganizer.groupBy(listOf(unauth, unknown), DeviceGroupBy.STATUS)
        assertEquals(listOf("status_offline"), groups.map { it.key })
    }

    @Test
    fun groupBy_SUBNET_orders_by_mru_then_numeric_tiebreak() {
        // Two subnets with equal max lastUsedAt → numeric tiebreak (10.0.0 < 192.168.1).
        val a = view("a", lastUsedAt = 5L, wirelessIp = "192.168.1.10", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val c = view("c", lastUsedAt = 5L, wirelessIp = "10.0.0.5", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(a, c), DeviceGroupBy.SUBNET)
        assertEquals(listOf("10.0.0", "192.168.1"), groups.map { it.key })
    }

    @Test
    fun groupBy_SUBNET_none_bucket_orders_by_mru_not_always_last() {
        // USB/no-IP bucket participates in MRU: if its member was used most recently, it sorts
        // first — not pinned to the end.
        val a = view("a", lastUsedAt = 1L, wirelessIp = "192.168.1.10", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val usb = view("u", lastUsedAt = 9L, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(a, usb), DeviceGroupBy.SUBNET)
        assertEquals(listOf("subnet_none", "192.168.1"), groups.map { it.key })
    }

    @Test
    fun groupBy_SUBNET_malformed_ip_goes_to_none_bucket() {
        val bad = view("x", lastUsedAt = 1L, wirelessIp = "not-an-ip", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(bad), DeviceGroupBy.SUBNET)
        assertEquals(listOf("subnet_none"), groups.map { it.key })
    }

    @Test
    fun groupBy_TAG_orders_by_mru_untagged_first_when_used_most_recently() {
        // The untagged device was used most recently → untagged bucket first (MRU overrides the
        // alphabetical base order).
        val z = view("z", lastUsedAt = 1L, tag = "zebra")
        val a = view("a", lastUsedAt = 2L, tag = "alpha")
        val none = view("n", lastUsedAt = 9L, tag = null)
        val groups = DeviceListOrganizer.groupBy(listOf(none, z, a), DeviceGroupBy.TAG)
        assertEquals(listOf("tag_none", "alpha", "zebra"), groups.map { it.key })
    }

    @Test
    fun groupBy_TAG_case_insensitive_alphabetical_tiebreak() {
        // Equal max lastUsedAt → case-insensitive alphabetical tiebreak (alpha < Beta).
        val a = view("a", lastUsedAt = 5L, tag = "Beta")
        val b = view("b", lastUsedAt = 5L, tag = "alpha")
        val groups = DeviceListOrganizer.groupBy(listOf(a, b), DeviceGroupBy.TAG)
        assertEquals(listOf("alpha", "Beta"), groups.map { it.key })
    }

    @Test
    fun groupBy_empty_input_returns_empty() {
        assertEquals(emptyList<DeviceGroup>(), DeviceListOrganizer.groupBy(emptyList(), DeviceGroupBy.TYPE))
    }
}
