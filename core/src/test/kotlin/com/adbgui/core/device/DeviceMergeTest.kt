package com.adbgui.core.device

import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.DeviceStatus
import com.adbgui.core.domain.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceMergeTest {

    @Test
    fun folds_history_entries_sharing_deviceId_into_one_view() {
        val hist = listOf(
            DeviceHistoryEntry(serial = "10.0.5.221:38897", type = DeviceType.WIRELESS, wirelessIp = "10.0.5.221", wirelessPort = 38897, alias = "我的手机", deviceId = "af199bb4", lastConnectedAt = 100),
            DeviceHistoryEntry(serial = "10.0.5.221:37751", type = DeviceType.WIRELESS, wirelessIp = "10.0.5.221", wirelessPort = 37751, deviceId = "af199bb4", lastConnectedAt = 200),
        )
        val live = listOf(DeviceSnapshot("10.0.5.221:37751", DeviceStatus.ONLINE))
        val views = DeviceMerge.merge(live, hist)
        assertEquals(1, views.size)
        assertEquals("10.0.5.221:37751", views[0].serial)      // rep = the online entry
        assertEquals("我的手机", views[0].alias)
        assertEquals(DeviceStatus.ONLINE, views[0].status)
        assertEquals(200, views[0].lastConnectedAt)
    }

    @Test
    fun usb_and_wireless_entries_with_same_deviceId_fold_to_wireless_view() {
        val hist = listOf(
            DeviceHistoryEntry(serial = "af199bb4", type = DeviceType.USB, deviceId = "af199bb4"),
            DeviceHistoryEntry(serial = "10.0.5.221:37751", type = DeviceType.WIRELESS, wirelessIp = "10.0.5.221", wirelessPort = 37751, deviceId = "af199bb4"),
        )
        val views = DeviceMerge.merge(emptyList(), hist)
        assertEquals(1, views.size)
        assertEquals(DeviceType.WIRELESS, views[0].type)
    }

    @Test
    fun live_only_device_without_history_gets_view() {
        val views = DeviceMerge.merge(listOf(DeviceSnapshot("serialX", DeviceStatus.ONLINE)), emptyList())
        assertEquals(1, views.size)
        assertEquals(DeviceStatus.ONLINE, views[0].status)
    }

    @Test
    fun entries_without_deviceId_stay_separate() {
        val hist = listOf(
            DeviceHistoryEntry(serial = "a:1", type = DeviceType.WIRELESS, wirelessIp = "a", wirelessPort = 1),
            DeviceHistoryEntry(serial = "a:2", type = DeviceType.WIRELESS, wirelessIp = "a", wirelessPort = 2),
        )
        assertEquals(2, DeviceMerge.merge(emptyList(), hist).size)   // no deviceId →保守不并
    }
}
