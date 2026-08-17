package com.adbgui.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellLauncherTest {
    @Test fun fake_captures_open_args() {
        val fake = FakeShellLauncher()
        assertNull(fake.lastAdbPath)
        fake.open("/path/adb", "10.0.6.100:5555")
        assertEquals("/path/adb", fake.lastAdbPath)
        assertEquals("10.0.6.100:5555", fake.lastSerial)
        assertEquals(1, fake.openCount)
    }

    @Test fun windows_buildArgs_cmd_fallback_when_no_wt() {
        val launcher = WindowsShellLauncher(wtAvailable = { false })
        val args = launcher.buildArgs("adb", "serial1")
        assertEquals(listOf("cmd.exe", "/K", "adb -s serial1 shell"), args)
    }

    @Test fun windows_buildArgs_wt_when_available() {
        val launcher = WindowsShellLauncher(wtAvailable = { true })
        val args = launcher.buildArgs("adb", "serial1")
        assertEquals(listOf("wt.exe", "cmd", "/K", "adb -s serial1 shell"), args)
    }

    @Test fun windows_buildArgs_quotes_adbpath_with_spaces() {
        val launcher = WindowsShellLauncher(wtAvailable = { false })
        val args = launcher.buildArgs("C:\\Program Files\\adb", "serial1")
        assertEquals(listOf("cmd.exe", "/K", "\"C:\\Program Files\\adb\" -s serial1 shell"), args)
    }
}
