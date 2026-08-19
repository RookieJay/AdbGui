package com.adbgui.core.domain

enum class ExtraType(val flag: String) {
    STRING("--es"), INT("--ei"), BOOL("--ez"), LONG("--el"),
}

data class Extra(val type: ExtraType, val key: String, val value: String)
