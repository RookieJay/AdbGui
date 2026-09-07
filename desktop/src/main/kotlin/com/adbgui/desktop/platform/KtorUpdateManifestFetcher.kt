package com.adbgui.desktop.platform

import com.adbgui.core.update.UpdateManifestFetcher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KtorUpdateManifestFetcher(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateManifestFetcher {
    private val client = HttpClient()

    override suspend fun fetch(url: String): String = withContext(io) {
        client.get(url).bodyAsText()
    }
}
