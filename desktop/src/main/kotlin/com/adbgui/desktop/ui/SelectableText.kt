package com.adbgui.desktop.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Text wrapped in SelectionContainer — mouse-drag selectable + Ctrl+C, no right-click menu conflict. */
@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.caption,
    color: Color = Color.Unspecified,
) {
    SelectionContainer {
        Text(text, modifier = modifier, style = style, color = color)
    }
}
