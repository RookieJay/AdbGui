package com.adbgui.desktop.main

import com.adbgui.core.adb.AdbLocator
import com.adbgui.core.adb.AdbServerController
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.DeviceTracker
import com.adbgui.core.device.WirelessReconnector
import com.adbgui.core.log.Logger
import com.adbgui.core.mdns.MdnsScanner
import com.adbgui.core.mdns.MdnsSocket
import com.adbgui.core.settings.SettingsStore
import com.adbgui.core.update.UpdateChecker
import com.adbgui.desktop.platform.AppMeta
import com.adbgui.desktop.platform.FileLogger
import com.adbgui.desktop.platform.JvmAdbProcessRunner
import com.adbgui.desktop.platform.JvmMdnsSocket
import com.adbgui.desktop.platform.KtorUpdateDownloader
import com.adbgui.desktop.platform.KtorUpdateManifestFetcher
import com.adbgui.desktop.platform.MsiUpgrader
import com.adbgui.desktop.platform.PortableUpdateNotifier
import com.adbgui.desktop.platform.ResourceBundledAdbProvider
import com.adbgui.desktop.platform.SystemPathProbe
import com.adbgui.desktop.platform.WindowsConfigDirProvider
import com.adbgui.desktop.ui.update.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CompositionRoot {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val configDir = WindowsConfigDirProvider().configDir()
    val logger: Logger = FileLogger(configDir.resolve("logs"), clock = { System.currentTimeMillis() })
    val settings = SettingsStore(configDir)
    // Log level follows settings (spec: user switches to DEBUG when troubleshooting). Without
    // this the FileLogger stays at its INFO default and the settings toggle is a dead switch.
    // SettingsStore loads asynchronously — collect covers both the default and the loaded value.
    init {
        logger.level = settings.state.value.logLevel
        scope.launch { settings.state.collect { logger.level = it.logLevel } }
    }
    private val runner = JvmAdbProcessRunner()
    val locator = AdbLocator(settings, ResourceBundledAdbProvider(), SystemPathProbe())
    val server = AdbServerController({ locator.locate() }, runner, logger)
    val commands = CommandRunner({ locator.locate() }, runner, logger, scope, CommandRunner.AdbServerStarter { server.ensureStarted() })
    val history = DeviceHistoryStore(configDir, clock = { System.currentTimeMillis() })
    val tracker = DeviceTracker({ locator.locate() }, server, runner, logger, scope)
    // mDNS scanner: bind failure degrades gracefully (no wireless discovery, app still runs).
    // Socket must be closed at shutdown — held as a val so stop() can reach it (no dead code).
    private val mdnsSocket: MdnsSocket? = runCatching { JvmMdnsSocket() }
        .onFailure { logger.warn("mdns: socket bind failed, wireless discovery disabled: ${it.message}") }
        .getOrNull()
    private val mdnsScanner: MdnsScanner? = mdnsSocket?.let { MdnsScanner(it, logger, scope) }
    val repository = DeviceRepository(tracker, history, commands, logger, scope, mdnsScanner)
    val reconnector = WirelessReconnector(repository, history, logger, scope, autoReconnectEnabled = { settings.state.value.autoReconnect })
    // scrcpy wiring (Task 5)
    val scrcpyInstaller = com.adbgui.desktop.platform.ScrcpyInstaller(configDir)
    val scrcpyLocator = com.adbgui.desktop.platform.WindowsScrcpyLocator(settings, configDir, SystemPathProbe())
    val scrcpyLauncher = com.adbgui.desktop.platform.WindowsScrcpyLauncher()
    // Update check wiring (Task 10)
    val updateFetcher = KtorUpdateManifestFetcher()
    val updateChecker = UpdateChecker(updateFetcher, AppMeta.APP_VERSION, logger)
    val updateDownloader = KtorUpdateDownloader(configDir, logger = logger)
    val msiUpgrader = MsiUpgrader(logger)
    val portableNotifier = PortableUpdateNotifier()
    val installedProbe = com.adbgui.desktop.platform.RegInstalledAppVersionProbe()
    val installedAppLauncher = com.adbgui.desktop.platform.InstalledAppLauncher()
    val updateViewModel = UpdateViewModel(updateChecker, settings, scope, updateDownloader, msiUpgrader, portableNotifier, installedProbe, installedAppLauncher, io = kotlinx.coroutines.Dispatchers.IO)

    // NOTE: the initial locale is set on the UI thread in Main (see Main.kt) — do NOT set
    // Strings here on the background scope, which would create the Compose state off the UI
    // thread and crash the first composition ("state created after the snapshot was taken").
    fun start() {
        scope.launch {
            logger.info("ADB GUI starting")
            tracker.start()
            mdnsScanner?.start()
            reconnector.start()
        }
    }

    /** App shutdown: release tracker + mdns scanner/reconnector + close the multicast socket.
     *  Called from Main's onDispose — mirrors the symmetry of start(). */
    fun stop() {
        tracker.stop()
        mdnsScanner?.stop()
        reconnector.stop()
        mdnsSocket?.close()
    }
}
