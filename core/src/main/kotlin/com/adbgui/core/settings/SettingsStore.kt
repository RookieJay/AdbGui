package com.adbgui.core.settings

import com.adbgui.core.domain.RemoteButton
import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
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
    val locale: String = "zh",
    val windowBounds: WindowBounds? = null,
    val remoteButtons: List<RemoteButton> = listOf(
        RemoteButton("vol_up", "音量+", 24),
        RemoteButton("vol_down", "音量−", 25),
        RemoteButton("vol_mute", "静音", 91),
        RemoteButton("power", "电源", 26),
        RemoteButton("app_switch", "应用切换", 187),
    ),
)

class SettingsStore(private val configDir: Path, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = configDir.resolve("settings.json")

    suspend fun load(): Settings = withContext(io) {
        if (!Files.exists(file)) return@withContext Settings()
        runCatching { json.decodeFromString<Settings>(Files.readString(file)) }.getOrDefault(Settings())
    }

    suspend fun save(settings: Settings) = withContext(io) {
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
