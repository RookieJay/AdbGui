package com.adbgui.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExceptionsTest {
    @Test
    fun adbCommandException_preserves_raw_stderr_and_command() {
        val ex = AdbCommandException(command = "install -r x.apk", exitCode = 1, stderr = "Failure [INSTALL_FAILED_OLDER_SDK]")
        assertEquals("install -r x.apk", ex.command)
        assertTrue(ex.stderr.contains("INSTALL_FAILED_OLDER_SDK"))
    }
}
