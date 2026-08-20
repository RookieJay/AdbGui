package com.adbgui.core.domain

data class ScrcpyOptions(
    val maxSize: Int = 0,  // 0 = native resolution; >0 = limit (e.g. 1920)
    val stayAwake: Boolean = true,
    val turnScreenOff: Boolean = false,
    val recordPath: String? = null,
    val alwaysOnTop: Boolean = false,
    val fullscreen: Boolean = false,
    val maxFps: Int = 0,  // 0 = unset; >0 = cap (e.g. 60)
    val noAudio: Boolean = false,
)
