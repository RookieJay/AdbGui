package com.adbgui.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModelsTest {
    @Test
    fun deviceView_defaults_isLive_false_when_status_offline() {
        val v = DeviceView(serial = "abc", status = DeviceStatus.OFFLINE)
        assertFalse(v.isLive)
        assertEquals(null, v.alias)
    }

    @Test
    fun deviceView_isLive_true_when_online() {
        val v = DeviceView(serial = "abc", status = DeviceStatus.ONLINE)
        assert(v.isLive)
    }
}
