package com.adbgui.desktop.platform

/** 便携版更新：用默认浏览器打开下载页（不自替换运行中目录）。 */
open class PortableUpdateNotifier {
    open fun openDownloadPage(url: String) {
        // Quote the URL so '&' (query-string separators) isn't parsed by `cmd /c start` as a
        // command separator. The empty title "" is start's window-title placeholder.
        ProcessBuilder("cmd", "/c", "start", "", "\"$url\"").redirectErrorStream(true).start()
    }
}
