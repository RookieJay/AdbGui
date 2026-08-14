package com.adbgui.core.domain

class AdbNotFoundException(message: String) : RuntimeException(message)
class NoDeviceSelectedException : RuntimeException("No device selected")

class AdbCommandException(
    val command: String,
    val exitCode: Int,
    val stderr: String,
    message: String = "adb command failed: $command (exit $exitCode)",
) : RuntimeException(message)
