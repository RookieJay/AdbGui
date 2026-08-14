package com.adbgui.desktop.main

import com.adbgui.core.adb.AdbLocator
import com.adbgui.core.adb.AdbServerController
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.DeviceTracker
import com.adbgui.core.log.Logger
import com.adbgui.core.settings.SettingsStore
import com.adbgui.desktop.platform.FileLogger
import com.adbgui.desktop.platform.JvmAdbProcessRunner
import com.adbgui.desktop.platform.ResourceBundledAdbProvider
import com.adbgui.desktop.platform.SystemPathProbe
import com.adbgui.desktop.platform.WindowsConfigDirProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CompositionRoot {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val configDir = WindowsConfigDirProvider().configDir()
    val logger: Logger = FileLogger(configDir.resolve("logs"), clock = { System.currentTimeMillis() })
    val settings = SettingsStore(configDir)
    private val runner = JvmAdbProcessRunner()
    private val locator = AdbLocator(settings, ResourceBundledAdbProvider(), SystemPathProbe())
    val server = AdbServerController({ locator.locate() }, runner, logger)
    val commands = CommandRunner({ locator.locate() }, runner, logger, scope, CommandRunner.AdbServerStarter { server.ensureStarted() })
    private val history = DeviceHistoryStore(configDir, clock = { System.currentTimeMillis() })
    val tracker = DeviceTracker({ locator.locate() }, server, runner, logger, scope, clock = { System.currentTimeMillis() })
    val repository = DeviceRepository(tracker, history, commands, logger, scope, clock = { System.currentTimeMillis() })

    fun start() {
        scope.launch { logger.info("ADB GUI starting"); tracker.start() }
    }
}
