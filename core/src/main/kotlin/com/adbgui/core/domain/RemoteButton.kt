package com.adbgui.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class RemoteButton(
    val id: String,
    val label: String,
    val keycode: Int,
)
