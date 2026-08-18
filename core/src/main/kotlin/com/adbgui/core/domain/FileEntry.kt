package com.adbgui.core.domain

data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val date: String,
    val permissions: String,
    val raw: String,
)
