package com.adbgui.core.adb

import com.adbgui.core.domain.DeviceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceListParsersTest {
    @Test
    fun parses_track_events_line_by_line() {
        assertNull(TrackDevicesParser.parseEvents("List of devices attached"))
        assertNull(TrackDevicesParser.parseEvents(""))
        assertEquals("emulator-5554", TrackDevicesParser.parseEvents("emulator-5554 device")?.serial)
        assertEquals(DeviceStatus.ONLINE, TrackDevicesParser.parseEvents("emulator-5554 device")?.status)
        assertEquals(DeviceStatus.OFFLINE, TrackDevicesParser.parseEvents("192.168.1.50:5555 offline")?.status)
        assertEquals(DeviceStatus.UNAUTHORIZED, TrackDevicesParser.parseEvents("abc unauthorized")?.status)
    }

    @Test
    fun devices_list_parser_parses_full_block() {
        val out = "List of devices attached\nabc device\nxyz offline\n"
        val list = DevicesListParser.parse(out)
        assertEquals(2, list.size)
        assertEquals("abc", list[0].serial)
        assertEquals(DeviceStatus.OFFLINE, list[1].status)
    }
}
