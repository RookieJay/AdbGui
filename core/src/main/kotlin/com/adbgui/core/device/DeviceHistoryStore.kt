package com.adbgui.core.device

import com.adbgui.core.domain.DeviceType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class DeviceHistoryEntry(
    val serial: String,
    val alias: String? = null,
    val type: DeviceType? = null,
    val wirelessIp: String? = null,
    val wirelessPort: Int? = null,
    val lastConnectedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val tag: String? = null,
)

class DeviceHistoryStore(
    private val configDir: Path,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = configDir.resolve("devices.json")

    suspend fun load(): List<DeviceHistoryEntry> = withContext(io) {
        if (!Files.exists(file)) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(DeviceHistoryEntry.serializer()), Files.readString(file)) }.getOrDefault(emptyList())
    }

    suspend fun upsert(serial: String, type: DeviceType, wirelessIp: String?, wirelessPort: Int?, alias: String? = null) =
        mutate { entries ->
            val existingIdx = entries.indexOfFirst { it.serial == serial }
            val existing = entries.getOrNull(existingIdx)
            val updated = (existing ?: DeviceHistoryEntry(serial)).copy(
                type = type,
                wirelessIp = wirelessIp,
                wirelessPort = wirelessPort,
                alias = alias ?: existing?.alias,
                lastConnectedAt = clock(),
            )
            if (existingIdx >= 0) entries.toMutableList().apply { this[existingIdx] = updated } else entries + updated
        }

    suspend fun setAlias(serial: String, alias: String?) = mutate { entries ->
        val existing = entries.indexOfFirst { it.serial == serial }
        if (existing >= 0) entries.map { if (it.serial == serial) it.copy(alias = alias) else it }
        else entries + DeviceHistoryEntry(serial = serial, alias = alias)
    }

    /** Stamp "last used" (selection / connect) for MRU sorting. Creates an entry for previously-
     *  unknown serials (e.g. a USB-only device that was never wireless-connected) so MRU sort can
     *  place it. Does NOT touch lastConnectedAt (that means "last wireless connect"). */
    suspend fun touchLastUsed(serial: String) = mutate { entries ->
        val idx = entries.indexOfFirst { it.serial == serial }
        if (idx >= 0) entries.map { if (it.serial == serial) it.copy(lastUsedAt = clock()) else it }
        else entries + DeviceHistoryEntry(serial = serial, lastUsedAt = clock())
    }

    /** Set a free-form grouping tag. null clears it. Creates an entry for unknown serials. */
    suspend fun setTag(serial: String, tag: String?) = mutate { entries ->
        val idx = entries.indexOfFirst { it.serial == serial }
        if (idx >= 0) entries.map { if (it.serial == serial) it.copy(tag = tag) else it }
        else entries + DeviceHistoryEntry(serial = serial, tag = tag)
    }

    /**
     * Clear [tag] from EVERY entry bearing it, in a single read-modify-write. This is the
     * atomic counterpart to looping `setTag(serial, null)` per device — which races on
     * concurrent mutate() calls (each starts from the same base file, last write wins, so only
     * one device's tag is actually removed).
     */
    suspend fun clearTag(tag: String) = mutate { entries ->
        entries.map { if (it.tag == tag) it.copy(tag = null) else it }
    }

    suspend fun remove(serial: String) = mutate { entries -> entries.filterNot { it.serial == serial } }

    private suspend fun mutate(transform: (List<DeviceHistoryEntry>) -> List<DeviceHistoryEntry>) =
        withContext(io) {
            Files.createDirectories(configDir)
            val current = if (Files.exists(file)) runCatching { json.decodeFromString(ListSerializer(DeviceHistoryEntry.serializer()), Files.readString(file)) }.getOrDefault(emptyList()) else emptyList()
            val next = transform(current)
            val tmp = file.resolveSibling("devices.json.tmp")
            Files.writeString(tmp, json.encodeToString(ListSerializer(DeviceHistoryEntry.serializer()), next))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
}
