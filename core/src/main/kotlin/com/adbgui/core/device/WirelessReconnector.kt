package com.adbgui.core.device

import com.adbgui.core.domain.DeviceType
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Layer 1 (spec §3): periodically retries `adb connect` for recently-used wireless history
 * entries. When a phone's screen wakes or it re-joins the network with the same port, this
 * re-establishes the connection within seconds; port-changed cases are handled by Layer 2/3.
 *
 * All mutable state ([backoffMs], [nextAttemptAt], [job]) is either confined to the single
 * loop coroutine or is a `@Volatile` reference written only by start/stop on the caller's
 * thread (typically the main thread). No cross-thread data race.
 */
class WirelessReconnector(
    private val repo: DeviceRepository,
    private val history: DeviceHistoryStore,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val autoReconnectEnabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var job: Job? = null

    // Per-serial backoff state — written and read only inside the loop coroutine.
    private val backoffMs = mutableMapOf<String, Long>()
    private val nextAttemptAt = mutableMapOf<String, Long>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        while (scope.isActive) {
            delay(TICK_MS)
            if (!autoReconnectEnabled()) continue
            val now = clock()
            val candidates = history.load()
                .filter { it.type == DeviceType.WIRELESS && it.wirelessIp != null && it.wirelessPort != null }
                .filter { (it.lastUsedAt ?: 0L) > now - SEVEN_DAYS_MS }
                .sortedByDescending { it.lastUsedAt ?: 0L }
                .take(MAX_CANDIDATES)
            val onlineSerials = repo.devices.value.filter { it.isLive }.map { it.serial }.toSet()
            for (c in candidates) {
                // Online → backoff reset (device came back on its own; stop retrying).
                if (c.serial in onlineSerials) {
                    backoffMs.remove(c.serial)
                    nextAttemptAt.remove(c.serial)
                    continue
                }
                // Not yet time for this serial's next attempt → skip.
                if ((nextAttemptAt[c.serial] ?: 0L) > now) continue
                // User explicitly disconnected this endpoint — a manual disconnect is an
                // intent, not an outage (repo clears the flag on manual reconnect).
                if (repo.isUserDisconnected("${c.wirelessIp}:${c.wirelessPort}")) continue
                val ok = try {
                    repo.connectWireless(c.wirelessIp!!, c.wirelessPort!!).success
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("auto-reconnect error for ${c.serial}: ${e.message}")
                    false
                }
                if (ok) {
                    logger.info("auto-reconnect: ${c.serial} back online")
                    // Success → reset to initial backoff (next retry waits 5s if it drops again).
                    backoffMs[c.serial] = INITIAL_BACKOFF_MS
                    nextAttemptAt[c.serial] = now + INITIAL_BACKOFF_MS
                } else {
                    // Failure → exponential backoff: current backoff is used for the next delay,
                    // then doubled (capped at MAX_BACKOFF_MS).
                    val current = (backoffMs[c.serial] ?: INITIAL_BACKOFF_MS).coerceAtMost(MAX_BACKOFF_MS)
                    backoffMs[c.serial] = (current * 2).coerceAtMost(MAX_BACKOFF_MS)
                    nextAttemptAt[c.serial] = now + current
                    logger.debug("auto-reconnect failed for ${c.serial}; next attempt in ${current}ms")
                }
            }
        }
    }

    companion object {
        private const val TICK_MS = 5_000L
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_CANDIDATES = 10
    }
}
