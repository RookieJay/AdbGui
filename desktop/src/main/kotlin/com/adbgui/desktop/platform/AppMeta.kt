package com.adbgui.desktop.platform

/**
 * 运行时版本真相源。更新检查用此比对 manifest.version。
 * 改版本时同步改 desktop/build.gradle.kts 的 packageVersion（两处需一致）。
 */
object AppMeta {
    const val APP_VERSION = "1.0.0"
}
