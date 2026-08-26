package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.theme.AppColors

enum class MessageKind { Error, Success }

/**
 * Inline message banner for error/success feedback. Replaces the ad-hoc
 * `Surface(color = Color(0xFFFFCDD2/…))` + `SelectableText` and bare-error-`Text` patterns
 * that were scattered across screens with three different color palettes.
 *
 * Container/onContainer come from [AppColors] (theme-aware: light + dark variants), so a
 * banner is readable in both themes — the old hardcoded `Color(0xFFFFCDD2)` was fine on light
 * but bare `Color.Black` log text was invisible on dark.
 *
 * @param text      always-visible message; when [details] is non-null it serves as the header label.
 * @param kind      Error or Success.
 * @param details   optional collapsible full content (e.g. raw adb output); renders an
 *                  expand/collapse toggle (reuses the existing `expand`/`collapse` i18n keys).
 * @param onDismiss optional dismiss callback; renders a "×" button. When present, the text sits
 *                  in a `weight(1f)` Box so a long message can't push the button off-screen.
 */
@Composable
fun InlineMessageBanner(
    text: String,
    kind: MessageKind,
    modifier: Modifier = Modifier,
    details: String? = null,
    onDismiss: (() -> Unit)? = null,
    initiallyExpanded: Boolean = false,
) {
    val ext = AppColors.current
    val (container, onContainer) = when (kind) {
        MessageKind.Error -> ext.errorContainer to ext.onErrorContainer
        MessageKind.Success -> ext.successContainer to ext.onSuccessContainer
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            var expanded by remember { mutableStateOf(initiallyExpanded) }
            val hasTrailing = details != null || onDismiss != null
            if (hasTrailing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // weight(1f) on the text container keeps any trailing button visible when
                    // the message is long (SelectableText wraps Text in SelectionContainer, so a
                    // modifier on it lands on the inner Text — wrap it in a Box instead).
                    Box(modifier = Modifier.weight(1f)) {
                        SelectableText(
                            text,
                            color = onContainer,
                            style = if (details != null) MaterialTheme.typography.subtitle2 else MaterialTheme.typography.caption,
                        )
                    }
                    if (details != null) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) Strings.t("collapse") else Strings.t("expand"))
                        }
                    }
                    if (onDismiss != null) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.padding(start = 4.dp).width(40.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = Strings.t("clear"))
                        }
                    }
                }
                if (details != null && expanded) {
                    SelectableText(details, color = onContainer, style = MaterialTheme.typography.caption)
                }
            } else {
                SelectableText(text, color = onContainer, style = MaterialTheme.typography.caption)
            }
        }
    }
}
