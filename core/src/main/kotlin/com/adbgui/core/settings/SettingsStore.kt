package com.adbgui.core.settings

import com.adbgui.core.domain.DeviceGroupBy
import com.adbgui.core.domain.RemoteButton
import com.adbgui.core.domain.ScrcpyLaunchProfile
import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class WindowBounds(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable
data class UpdateSettings(
    val sourceId: String = "github-official",
    val lastCheckAt: String? = null,
    val lastCheckError: String? = null,
    val checkOnStartup: Boolean = true,
    val dismissedVersion: String? = null,
    /** Persisted Ready state so a downloaded-but-not-installed MSI survives app restart. */
    val readyMsiPath: String? = null,
    val readyVersion: String? = null,
    val readySha256: String? = null,
)

@Serializable
data class Settings(
    val adbPathOverride: String? = null,
    val logLevel: LogLevel = LogLevel.INFO,
    val theme: String = "system",
    val locale: String = "zh",
    val windowBounds: WindowBounds? = null,
    val scrcpyPathOverride: String? = null,
    val scrcpyDownloadUrl: String? = null,  // null = GitHub releases
    val scrcpyMode: String = "EXTERNAL",  // EMBEDDED / EXTERNAL
    val scrcpyLaunch: ScrcpyLaunchProfile = ScrcpyLaunchProfile(),
    val deviceGroupBy: DeviceGroupBy = DeviceGroupBy.NONE,
    /** In-memory logcat ring buffer cap (lines). Mirrors Android Studio's "Logcat cycle buffer
     *  size" — larger = more history retained for copy/scrollback, at the cost of memory. Applied
     *  at LogcatController construction, so a change takes effect on next app start. */
    val logcatRingCap: Int = 50000,
    val remoteButtons: List<RemoteButton> = listOf(
        RemoteButton("vol_up", "音量+", 24),
        RemoteButton("vol_down", "音量−", 25),
        RemoteButton("vol_mute", "静音", 91),
        RemoteButton("power", "电源", 26),
        RemoteButton("app_switch", "应用切换", 187),
    ),
    val update: UpdateSettings = UpdateSettings(),
)

class SettingsStore(private val configDir: Path, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = configDir.resolve("settings.json")
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    suspend fun load(): Settings = withContext(io) {
        val loaded = if (!Files.exists(file)) Settings()
        else runCatching { json.decodeFromString<Settings>(Files.readString(file)) }.getOrDefault(Settings())
        _state.value = loaded
        loaded
    }

    suspend fun save(settings: Settings) = withContext(io) {
        Files.createDirectories(configDir)
        val tmp = file.resolveSibling("settings.json.tmp")
        Files.writeString(tmp, json.encodeToString(Settings.serializer(), settings))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _state.value = settings
        Unit
    }

    suspend fun update(transform: (Settings) -> Settings) {
        save(transform(_state.value))
    }
}
