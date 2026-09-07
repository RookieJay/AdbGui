package com.adbgui.core.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long? = null,
    val notes: String? = null,
    val minAppVersion: String? = null,
)
