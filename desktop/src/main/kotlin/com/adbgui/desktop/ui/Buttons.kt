package com.adbgui.desktop.ui

import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ContentAlpha
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.RowScope

/**
 * Filled destructive-action button: red surface + on-error text. Use for the confirm button of
 * irreversible/disruptive confirmations (forget, uninstall, clear-data, clear-log, reboot) so the
 * danger is visually separated from the primary action (destructive-emphasis). Pairs with a plain
 * `TextButton` dismiss on the other side.
 */
@Composable
fun DangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val error = MaterialTheme.colors.error
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = error,
            contentColor = MaterialTheme.colors.onError,
            disabledBackgroundColor = error.copy(alpha = ContentAlpha.disabled),
            disabledContentColor = MaterialTheme.colors.onError.copy(alpha = ContentAlpha.disabled),
        ),
        content = content,
    )
}
