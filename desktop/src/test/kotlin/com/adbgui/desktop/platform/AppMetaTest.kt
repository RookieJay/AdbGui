package com.adbgui.desktop.platform

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * AppMeta.APP_VERSION 的真相源是根 gradle.properties 的 version=…，
 * desktop 的 processResources 生成 /version.properties 打进 jar，AppMeta 运行时读取。
 * 此测试验证生成链路没断：资源在 classpath 上且与 AppMeta 报告的一致。
 */
class AppMetaTest {
    @Test fun appVersionComesFromGeneratedVersionProperties() {
        val resourceVersion = javaClass.getResourceAsStream("/version.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("app.version")
        }
        assertNotNull(
            resourceVersion,
            "/version.properties missing on classpath — desktop processResources generation broken",
        )
        assertEquals(resourceVersion, AppMeta.APP_VERSION)
    }
}
