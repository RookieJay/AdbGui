package com.adbgui.desktop.platform

import com.adbgui.core.log.Logger

/**
 * Logs uncaught exceptions from any thread (including the AWT event dispatch thread) to the
 * app log instead of letting them vanish. Before this, the 2026-10-13 clipboard crash left
 * zero log output, making post-mortem diagnosis guesswork.
 *
 * This is a diagnosis safety net, not recovery: the handler returns normally so the AWT event
 * dispatch thread survives, but the code path that threw is still broken and should be fixed
 * at its source.
 */
object CrashGuard {
    @Volatile
    internal var logger: Logger? = null

    fun install(appLogger: Logger) {
        logger = appLogger
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Fall back to stderr if logging itself fails (e.g. mid-shutdown).
            runCatching { appLogger.error("uncaught exception on thread '${thread.name}'", throwable) }
                .onFailure { throwable.printStackTrace() }
        }
        // AWT instantiates this class by reflection when an exception escapes event dispatch:
        // it must stay public with a public no-arg constructor, and handle() returning normally
        // tells AWT the exception is handled (the app keeps running).
        System.setProperty("sun.awt.exception.handler", AwtEventDispatchLogger::class.java.name)
    }
}

class AwtEventDispatchLogger {
    fun handle(t: Throwable) {
        runCatching { CrashGuard.logger?.error("uncaught exception in AWT event dispatch", t) }
            .onFailure { t.printStackTrace() }
    }
}
