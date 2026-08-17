package com.adbgui.core.domain

enum class RebootMode(val arg: String?) {
    NORMAL(null),
    RECOVERY("recovery"),
    BOOTLOADER("bootloader"),
    SIDELOAD("sideload"),
}
