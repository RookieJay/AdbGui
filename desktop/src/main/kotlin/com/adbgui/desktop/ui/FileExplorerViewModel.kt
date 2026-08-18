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

    private val backStack = ArrayDeque<String>()

    fun navigate(path: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            val stdout = repo.ls(serial, path)
            val parsed = LsParser.parse(stdout)
            // push current to backStack (if navigating to a different path)
            if (path != _currentPath.value) backStack.addLast(_currentPath.value)
            _currentPath.value = path
            _entries.value = parsed.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
        } finally { _busy.value = false }
    }

    fun back() = scope.launch {
        if (backStack.isEmpty()) return@launch
        val prev = backStack.removeLast()
        // navigate without pushing to backStack
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            val stdout = repo.ls(serial, prev)
            _currentPath.value = prev
            _entries.value = LsParser.parse(stdout).sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
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
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
        } finally { _busy.value = false }
    }

    fun pull(devicePath: String, localSavePath: String) = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            repo.pull(serial, devicePath, localSavePath)
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
        } finally { _busy.value = false }
    }

    private val refreshJob: Job = scope.launch { selectedSerial.collect { it?.let { navigate("/") } } }
    fun stop() { refreshJob.cancel() }
}
