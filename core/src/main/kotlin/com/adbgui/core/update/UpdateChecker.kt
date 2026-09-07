package com.adbgui.core.update

import com.adbgui.core.log.Logger

class UpdateChecker(
    private val fetcher: UpdateManifestFetcher,
    private val currentVersion: String,
    private val logger: Logger,
) {
    suspend fun check(source: UpdateSource): UpdateCheckResult {
        logger.info("update: checking source=${source.id} current=$currentVersion")
        val raw = try {
            fetcher.fetch(source.manifestUrl)
        } catch (t: Throwable) {
            logger.warn("update: fetch failed source=${source.id}", t)
            return UpdateCheckResult.Error("fetch failed: ${t.message}")
        }
        val manifest = try {
            UpdateManifestParser.parse(raw)
        } catch (e: UpdateManifestParseException) {
            logger.warn("update: manifest parse failed source=${source.id}", e)
            return UpdateCheckResult.Error("invalid manifest", raw = e.raw, cause = e)
        }
        return if (UpdateVersionComparer.isNewer(manifest.version, currentVersion)) {
            logger.info("update: available ${manifest.version}")
            UpdateCheckResult.UpdateAvailable(manifest)
        } else {
            logger.info("update: no update (remote=${manifest.version})")
            UpdateCheckResult.NoUpdate
        }
    }
}
