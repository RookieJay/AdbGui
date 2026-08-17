package com.adbgui.desktop.platform

interface ShellLauncher {
    /** Launch an OS terminal running `<adbPath> -s <serial> shell`, detached. */
    fun open(adbPath: String, serial: String)
}

class FakeShellLauncher : ShellLauncher {
    var lastAdbPath: String? = null; private set
    var lastSerial: String? = null; private set
    var openCount: Int = 0; private set
    override fun open(adbPath: String, serial: String) {
        lastAdbPath = adbPath; lastSerial = serial; openCount++
    }
}

class WindowsShellLauncher(private val wtAvailable: () -> Boolean = ::defaultWtAvailable) : ShellLauncher {
    override fun open(adbPath: String, serial: String) {
        // detached — don't waitFor; the OS terminal outlives the app's interest.
        ProcessBuilder(buildArgs(adbPath, serial)).redirectErrorStream(true).start()
    }

    /** Pure + testable: returns the argv list (wt.exe vs cmd.exe, adb path quoted if it has spaces). */
    fun buildArgs(adbPath: String, serial: String): List<String> {
        val quoted = if (adbPath.contains(' ')) "\"$adbPath\"" else adbPath
        val cmd = "$quoted -s $serial shell"
        return if (wtAvailable()) listOf("wt.exe", "cmd", "/K", cmd)
               else listOf("cmd.exe", "/K", cmd)
    }

    companion object {
        private fun defaultWtAvailable(): Boolean =
            runCatching { ProcessBuilder("where", "wt.exe").redirectErrorStream(true).start().waitFor() == 0 }
                .getOrDefault(false)
    }
}
