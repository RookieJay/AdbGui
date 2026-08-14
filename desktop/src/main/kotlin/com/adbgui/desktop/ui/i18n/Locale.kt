package com.adbgui.desktop.ui.i18n

enum class Locale(val code: String, val display: String) {
    ZH("zh", "中文"),
    EN("en", "English");

    companion object {
        fun fromCode(code: String?): Locale = entries.firstOrNull { it.code == code } ?: ZH
    }
}
