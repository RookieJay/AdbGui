package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/**
 * Hover-tooltip wrapper for icon-only controls (Material 2 has no built-in tooltip).
 * After hovering [delayMillis] the text appears in a non-focusable popup below the
 * control; leaving hides it immediately. Also shows for disabled controls — the user
 * often wants to know exactly what the greyed-out button would do.
 *
 * `text` is a lambda (re-evaluated on locale switch); pass the same i18n key the
 * icon's contentDescription uses, e.g. `Tooltip(text = { Strings.t("copy") })`.
 */
@Composable
fun Tooltip(
    text: () -> String,
    modifier: Modifier = Modifier,
    delayMillis: Int = 500,
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(delayMillis.toLong())
            visible = true
        } else {
            visible = false
        }
    }
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    // Anchor height in px, so the popup can be pushed fully BELOW the control.
    // TopStart alignment already pins the popup's top-left to the anchor's top-left
    // (BottomStart would align the tooltip INSIDE the control's bottom half — the
    // popup window then sits under the cursor, Enter/Exit storm = visible flicker).
    var anchorHeight by remember { mutableStateOf(0) }
    Box(
        modifier
            .onSizeChanged { anchorHeight = it.height }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> hovered = false
                            else -> {}
                        }
                    }
                }
            }
    ) {
        content()
        if (visible) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, anchorHeight + gap),
                properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
            ) {
                Surface(elevation = 4.dp, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        text(),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        }
    }
}

/**
 * [IconButton] + [Tooltip] in one call — the standard shape for icon-only buttons.
 * Tooltip text must be supplied separately from the icon's contentDescription
 * (same key is fine) because [IconButton] swallows its content.
 */
@Composable
fun TooltipIconButton(
    onClick: () -> Unit,
    tooltip: () -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Tooltip(text = tooltip) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}
