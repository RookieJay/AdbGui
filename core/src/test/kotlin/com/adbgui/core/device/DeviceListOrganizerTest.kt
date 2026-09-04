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
    fun groupBy_TYPE_puts_USB_first_then_wireless_each_mru_sorted() {
        val usb1 = view("u1", lastUsedAt = 100L, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val usb2 = view("u2", lastUsedAt = 300L, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val wl1 = view("w1", lastUsedAt = 50L, type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE, wirelessIp = "10.0.0.1")
        val wl2 = view("w2", lastUsedAt = 999L, type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE, wirelessIp = "10.0.0.2")
        val groups = DeviceListOrganizer.groupBy(listOf(wl2, usb1, wl1, usb2), DeviceGroupBy.TYPE)
        assertEquals(listOf("type_usb", "type_wireless"), groups.map { it.key })
        assertEquals(listOf("u2", "u1"), groups[0].devices.map { it.serial })
        assertEquals(listOf("w2", "w1"), groups[1].devices.map { it.serial })
    }

    @Test
    fun groupBy_TYPE_unknown_type_goes_to_unknown_bucket_last() {
        val usb = view("u1", lastUsedAt = 1L, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val unk = view("k1", lastUsedAt = 2L, type = null, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(unk, usb), DeviceGroupBy.TYPE)
        assertEquals(listOf("type_usb", "type_unknown"), groups.map { it.key })
    }

    @Test
    fun groupBy_STATUS_online_first_then_offline() {
        val off1 = view("o1", lastUsedAt = 500L, status = DeviceStatus.OFFLINE)
        val on1 = view("n1", lastUsedAt = 10L, status = DeviceStatus.ONLINE)
        val on2 = view("n2", lastUsedAt = 20L, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(off1, on1, on2), DeviceGroupBy.STATUS)
        assertEquals(listOf("status_online", "status_offline"), groups.map { it.key })
        assertEquals(listOf("n2", "n1"), groups[0].devices.map { it.serial })
        assertEquals(listOf("o1"), groups[1].devices.map { it.serial })
    }

    @Test
    fun groupBy_STATUS_treats_unauthorized_and_unknown_as_offline() {
        val unauth = view("ua", lastUsedAt = 1L, status = DeviceStatus.UNAUTHORIZED)
        val unknown = view("uk", lastUsedAt = 2L, status = DeviceStatus.UNKNOWN)
        val groups = DeviceListOrganizer.groupBy(listOf(unauth, unknown), DeviceGroupBy.STATUS)
        assertEquals(listOf("status_offline"), groups.map { it.key })
    }

    @Test
    fun groupBy_SUBNET_groups_by_24_and_orders_numeric_usb_last() {
        val a = view("a", lastUsedAt = 1L, wirelessIp = "192.168.1.10", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val b = view("b", lastUsedAt = 2L, wirelessIp = "192.168.1.20", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val c = view("c", lastUsedAt = 3L, wirelessIp = "10.0.0.5", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val usb = view("u", lastUsedAt = 4L, type = DeviceType.USB, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(a, b, c, usb), DeviceGroupBy.SUBNET)
        // 10.0.0 < 192.168.1 numerically, USB/no-IP bucket last
        assertEquals(listOf("10.0.0", "192.168.1", "subnet_none"), groups.map { it.key })
        assertEquals(listOf("c"), groups[0].devices.map { it.serial })
        assertEquals(listOf("b", "a"), groups[1].devices.map { it.serial })
        assertEquals(listOf("u"), groups[2].devices.map { it.serial })
    }

    @Test
    fun groupBy_SUBNET_malformed_ip_goes_to_none_bucket() {
        val bad = view("x", lastUsedAt = 1L, wirelessIp = "not-an-ip", type = DeviceType.WIRELESS, status = DeviceStatus.ONLINE)
        val groups = DeviceListOrganizer.groupBy(listOf(bad), DeviceGroupBy.SUBNET)
        assertEquals(listOf("subnet_none"), groups.map { it.key })
    }

    @Test
    fun groupBy_TAG_alphabetical_untagged_last() {
        val z = view("z", lastUsedAt = 1L, tag = "zebra")
        val a = view("a", lastUsedAt = 2L, tag = "alpha")
        val none = view("n", lastUsedAt = 3L, tag = null)
        val groups = DeviceListOrganizer.groupBy(listOf(none, z, a), DeviceGroupBy.TAG)
        assertEquals(listOf("alpha", "zebra", "tag_none"), groups.map { it.key })
    }

    @Test
    fun groupBy_TAG_case_insensitive_order() {
        val a = view("a", lastUsedAt = 1L, tag = "Beta")
        val b = view("b", lastUsedAt = 2L, tag = "alpha")
        val groups = DeviceListOrganizer.groupBy(listOf(a, b), DeviceGroupBy.TAG)
        assertEquals(listOf("alpha", "Beta"), groups.map { it.key })
    }

    @Test
    fun groupBy_empty_input_returns_empty() {
        assertEquals(emptyList<DeviceGroup>(), DeviceListOrganizer.groupBy(emptyList(), DeviceGroupBy.TYPE))
    }
}
