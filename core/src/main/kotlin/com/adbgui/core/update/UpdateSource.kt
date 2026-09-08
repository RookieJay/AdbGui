package com.adbgui.core.update

data class UpdateSource(
    val id: String,
    val displayName: String,
    val manifestUrl: String,
    val proxyPrefix: String? = null,
)
