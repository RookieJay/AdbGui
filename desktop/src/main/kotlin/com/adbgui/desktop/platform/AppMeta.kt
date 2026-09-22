package com.adbgui.desktop.platform

import java.util.Properties

/**
 * 运行时版本真相源。更新检查用此比对 manifest.version。
 * 版本来自 Gradle processResources 生成的 /version.properties（唯一来源：根
 * gradle.properties 的 version=…）。改版本只改 gradle.properties 一处。
 */
object AppMeta {
    /** 资源缺失（异常打包）时的兜底：更新检查会永远提示有新版，坏得显眼而不是静默。 */
    const val FALLBACK_VERSION = "0.0.0-dev"

    val APP_VERSION: String by lazy {
        AppMeta::class.java.getResourceAsStream("/version.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("app.version")
        } ?: FALLBACK_VERSION
    }
}
