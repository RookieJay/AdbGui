package com.adbgui.core.domain

enum class LogcatLevel { V, D, I, W, E, F }

data class LogcatLine(
    val raw: String,
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: LogcatLevel,
    val tag: String,
    val message: String,
)
