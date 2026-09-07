package com.adbgui.core.update

sealed class UpdateDownloadResult {
    data class Success(val msiPath: String) : UpdateDownloadResult()
    data class HashMismatch(val expected: String, val actual: String) : UpdateDownloadResult()
    data class NetworkError(val message: String, val cause: Throwable? = null) : UpdateDownloadResult()
    object Cancelled : UpdateDownloadResult()
}
