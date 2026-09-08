package com.adbgui.desktop.platform

import com.adbgui.core.update.UpdateManifestFetcher
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KtorUpdateManifestFetcher(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateManifestFetcher {
    private val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        install(UserAgent) { agent = "AdbGui/${AppMeta.APP_VERSION}" }
    }

    override suspend fun fetch(url: String): String = withContext(io) {
        val resp = client.get(url)
        if (!resp.status.isSuccess()) {
            throw java.io.IOException("HTTP ${resp.status.value}")
        }
        resp.bodyAsText()
    }
}
