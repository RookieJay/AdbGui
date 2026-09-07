package com.adbgui.core.update

sealed class UpdateCheckResult {
    object NoUpdate : UpdateCheckResult()
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateCheckResult()
    data class Error(
        val message: String,
        val raw: String? = null,
        val cause: Throwable? = null,
    ) : UpdateCheckResult()
}
