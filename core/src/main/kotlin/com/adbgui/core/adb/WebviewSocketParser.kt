package com.adbgui.core.adb

/** Pure parser for `adb shell cat /proc/net/unix` output — finds the WebView devtools socket.
 *  The WebView's devtools abstract socket is named `webview_devtools_remote_<pid>` (the `@`
 *  prefix shown in /proc/net/unix for abstract sockets is NOT part of the name adb forward
 *  localabstract wants). Returns the bare socket name, or null if no WebView is running. */
object WebviewSocketParser {
    private val re = Regex("webview_devtools_remote_\\d+")

    fun parse(procNetUnixOutput: String): String? = re.find(procNetUnixOutput)?.value
}
