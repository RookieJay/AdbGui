package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Small status indicator dot. Green = online, gray = offline. Color is never the sole signal:
 * the device's alias/serial text sits beside it wherever the dot is used (device rows, top bar),
 * satisfying color-not-only.
 *
 * Shared between [DeviceListPane] rows and the [AppShell] top bar — extracted from the former's
 * private copy so both render identically.
 */
@Composable
fun StatusDot(
    isLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (isLive) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    Box(modifier = modifier.size(10.dp).background(color, shape = CircleShape))
}
