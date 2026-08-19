package com.adbgui.core.domain

class AdbNotFoundException(message: String) : RuntimeException(message)

class AdbCommandException(
    val command: String,
    val exitCode: Int,
    val stderr: String,
    message: String = "adb command failed: $command (exit $exitCode)",
) : RuntimeException(message)
