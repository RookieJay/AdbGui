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
    /** Optional portable (no-install) build download URL — shown as a "下载便携版" button when present. */
    val portableUrl: String? = null,
)
