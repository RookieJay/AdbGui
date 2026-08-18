package com.adbgui.desktop.ui

import com.adbgui.core.adb.LsParser
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileExplorerViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: kotlinx.coroutines.flow.StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()
    private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
    val entries: StateFlow<List<FileEntry>> = _entries.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _savedFile = MutableStateFlow<java.io.File?>(null)
    val savedFile: StateFlow<java.io.File?> = _savedFile.asStateFlow()

    private val backStack = ArrayDeque<String>()

    /** ls + parse + batch test -d for symlinks + sort. Shared by navigate/back. 15s timeout. */
    private suspend fun listAndClassify(serial: String, path: String): List<FileEntry> {
        val stdout = kotlinx.coroutines.withTimeoutOrNull(15_000) {
            repo.ls(serial, path)
        } ?: throw RuntimeException("unable to list entries: $path (timeout)")
        var parsed = LsParser.parse(stdout)
        val symlinks = parsed.filter { it.isSymlink }
        if (symlinks.isNotEmpty()) {
            val basePath = if (path.endsWith("/")) path else "$path/"
            val paths = symlinks.map { e -> "$basePath${e.name}" }
            val isDirs = repo.checkSymlinkDirs(serial, paths)
            symlinks.forEachIndexed { i, e ->
                val idx = parsed.indexOf(e)
                if (isDirs.getOrElse(i) { false }) {
                    parsed = parsed.toMutableList().also { it[idx] = e.copy(isDirectory = true) }
                }
            }
        }
        return parsed.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
    }

    fun navigate(path: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        val normPath = path.replace(Regex("/+"), "/").let { if (it.length > 1) it.trimEnd('/') else it }
        _busy.value = true; _error.value = null
        try {
            val entries = listAndClassify(serial, normPath)
            if (normPath != _currentPath.value) backStack.addLast(_currentPath.value)
            _currentPath.value = normPath
            _entries.value = entries
        } catch (e: Exception) {
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
        } finally { _busy.value = false }
    }

    fun back() = scope.launch {
        if (backStack.isEmpty()) return@launch
        val prev = backStack.removeLast()
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            val entries = listAndClassify(serial, prev)
            _currentPath.value = prev
            _entries.value = entries
        } catch (e: Exception) {
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
        } finally { _busy.value = false }
    }

    fun refresh() = scope.launch {
        navigate(_currentPath.value)
    }

    fun push(localPath: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        val fileName = localPath.substringAfterLast('/')
        val devicePath = if (_currentPath.value.endsWith("/")) "${_currentPath.value}$fileName" else "${_currentPath.value}/$fileName"
        _busy.value = true; _error.value = null
        try {
            repo.push(serial, localPath, devicePath)
            refresh()
        } catch (e: Exception) {
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
        } finally { _busy.value = false }
    }

    fun pull(devicePath: String, localSavePath: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null; _savedFile.value = null
        try {
            repo.pull(serial, devicePath, localSavePath)
            _savedFile.value = java.io.File(localSavePath)
        } catch (e: Exception) {
            _error.value = if (e is AdbCommandException) "${e.message}\n--- adb stderr ---\n${e.stderr}" else (e.message ?: "unknown error")
        } finally { _busy.value = false }
    }

    private val refreshJob: Job = scope.launch { selectedSerial.collect { it?.let { navigate("/") } } }
    fun stop() { refreshJob.cancel() }
}
