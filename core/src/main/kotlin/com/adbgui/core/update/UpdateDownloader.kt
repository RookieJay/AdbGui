package com.adbgui.core.update

interface UpdateDownloader {
    suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit): UpdateDownloadResult
}
