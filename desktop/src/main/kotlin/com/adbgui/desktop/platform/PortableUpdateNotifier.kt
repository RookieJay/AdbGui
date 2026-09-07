package com.adbgui.desktop.platform

/** 便携版更新：用默认浏览器打开下载页（不自替换运行中目录）。 */
open class PortableUpdateNotifier {
    open fun openDownloadPage(url: String) {
        ProcessBuilder("cmd", "/c", "start", "", url).redirectErrorStream(true).start()
    }
}
