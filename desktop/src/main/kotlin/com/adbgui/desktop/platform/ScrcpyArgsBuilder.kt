package com.adbgui.desktop.platform

import com.adbgui.core.domain.ScrcpyOptions

/**
 * Pure builder for the scrcpy CLI argument list. No I/O — fully testable.
 *
 * Keeps arg-building out of [ScrcpyLauncher] so the launcher only does process
 * orchestration. Flag order is irrelevant to scrcpy; we emit a stable order for
 * readable logs. See docs/superpowers/plans/2026-08-20-scrcpy-options.md for the
 * flag map (verified against `scrcpy.exe --help`, v4.1).
 */
object ScrcpyArgsBuilder {
    fun build(scrcpyPath: String, serial: String, options: ScrcpyOptions): List<String> = buildList {
        add(scrcpyPath)
        add("-s"); add(serial)
        if (options.maxSize > 0) {
            add("--max-size"); add(options.maxSize.toString())
        }
        if (options.stayAwake) add("--stay-awake")
        if (options.turnScreenOff) add("--turn-screen-off")
        val record = options.recordPath
        if (!record.isNullOrBlank()) {
            add("--record"); add(record)
        }
        if (options.alwaysOnTop) add("--always-on-top")
        if (options.fullscreen) add("--fullscreen")
        if (options.maxFps > 0) {
            add("--max-fps"); add(options.maxFps.toString())
        }
        if (options.noAudio) add("--no-audio")
    }
}
