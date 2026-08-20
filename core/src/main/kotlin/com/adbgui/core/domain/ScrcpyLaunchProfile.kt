package com.adbgui.core.domain

import kotlinx.serialization.Serializable

/**
 * Persisted scrcpy launch options (user's last-used profile), stored in
 * [com.adbgui.core.settings.Settings]. Mirrors [ScrcpyOptions] except [recordFolder] holds the
 * folder to record into (null/blank = no recording); the per-launch timestamped filename is built
 * at launch time in the UI, not persisted. Defaults mirror ScrcpyOptions defaults.
 */
@Serializable
data class ScrcpyLaunchProfile(
    val maxSize: Int = 0,
    val stayAwake: Boolean = true,
    val turnScreenOff: Boolean = false,
    val alwaysOnTop: Boolean = false,
    val fullscreen: Boolean = false,
    val maxFps: Int = 0,
    val noAudio: Boolean = false,
    val recordFolder: String? = null,
)
