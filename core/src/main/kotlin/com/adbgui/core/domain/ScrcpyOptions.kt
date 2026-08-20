package com.adbgui.core.domain

data class ScrcpyOptions(
    val maxSize: Int = 0,  // 0 = native resolution; >0 = limit (e.g. 1920)
    val stayAwake: Boolean = true,
    val turnScreenOff: Boolean = false,
    val recordPath: String? = null,
)
