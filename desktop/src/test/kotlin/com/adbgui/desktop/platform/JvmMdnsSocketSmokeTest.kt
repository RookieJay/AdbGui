package com.adbgui.desktop.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class JvmMdnsSocketSmokeTest {
    @Test
    fun open_receive_timeout_and_close_roundtrip() = runBlocking {
        val s = JvmMdnsSocket()
        try {
            // 无网络依赖的断言：5353 绑定成功（与 adb server 共存靠 SO_REUSEADDR）+ 超时返回 null
            val pkt = s.receive(200)
            kotlin.test.assertNull(pkt)
        } finally { s.close() }
    }
}
