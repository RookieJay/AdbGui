package com.adbgui.core.update

interface UpdateManifestFetcher {
    suspend fun fetch(url: String): String
}
