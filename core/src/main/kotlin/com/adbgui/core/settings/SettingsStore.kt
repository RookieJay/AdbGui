package com.adbgui.core.settings

import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class WindowBounds(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable
data class Settings(
    val adbPathOverride: String? = null,
    val logLevel: LogLevel = LogLevel.INFO,
    val theme: String = "system",
    val windowBounds: WindowBounds? = null,
)

class SettingsStore(private val configDir: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = configDir.resolve("settings.json")

    suspend fun load(): Settings = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext Settings()
        runCatching { json.decodeFromString<Settings>(Files.readString(file)) }.getOrDefault(Settings())
    }

    suspend fun save(settings: Settings) = withContext(Dispatchers.IO) {
        Files.createDirectories(configDir)
        val tmp = file.resolveSibling("settings.json.tmp")
        Files.writeString(tmp, json.encodeToString(Settings.serializer(), settings))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    suspend fun update(transform: (Settings) -> Settings) {
        val current = load()
        save(transform(current))
    }
}
