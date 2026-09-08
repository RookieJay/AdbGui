package com.adbgui.desktop.platform

import com.adbgui.core.log.Logger
import com.adbgui.core.update.UpdateDownloadResult
import com.adbgui.core.update.UpdateDownloader
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class KtorUpdateDownloader(
    private val configDir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logger: Logger,
) : UpdateDownloader {
    private val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        install(UserAgent) { agent = "AdbGui/${AppMeta.APP_VERSION}" }
    }

    override suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit): UpdateDownloadResult = withContext(io) {
        val updatesDir = configDir.resolve("updates").also { Files.createDirectories(it) }
        val partFile = updatesDir.resolve("$sha256.msi.part")
        val finalFile = updatesDir.resolve("$sha256.msi")
        runCatching {
            Files.list(updatesDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && (it.toString().endsWith(".msi") || it.toString().endsWith(".msi.part")) }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }.onFailure { logger.warn("update: failed to clean up stale .msi files", it) }
        try {
            logger.info("update: starting download $url")
            val resp = client.get(url)
            logger.info("update: response ${resp.status.value}, contentLength=${resp.contentLength()}")
            if (!resp.status.isSuccess()) {
                Files.deleteIfExists(partFile)
                logger.warn("update: http ${resp.status.value} for $url")
                return@withContext UpdateDownloadResult.NetworkError("HTTP ${resp.status.value}")
            }
            val channel = resp.bodyAsChannel()
            val total = resp.contentLength()?.takeIf { it > 0 } ?: -1L
            var read = 0L
            val md = MessageDigest.getInstance("SHA-256")
            if (total < 0) onProgress(-1f)
            Files.newOutputStream(partFile).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = channel.readAvailable(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    read += n
                    if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) {
                Files.deleteIfExists(partFile)
                logger.warn("update: sha256 mismatch expected=$sha256 actual=$actual")
                return@withContext UpdateDownloadResult.HashMismatch(sha256, actual)
            }
            Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            logger.info("update: downloaded ${finalFile.fileName} ($read bytes)")
            UpdateDownloadResult.Success(finalFile.toString())
        } catch (t: kotlinx.coroutines.CancellationException) {
            Files.deleteIfExists(partFile)
            throw t
        } catch (t: Throwable) {
            Files.deleteIfExists(partFile)
            logger.warn("update: download failed", t)
            UpdateDownloadResult.NetworkError(t.message ?: "unknown", t)
        }
    }
}
