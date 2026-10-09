package com.adbgui.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.theme.AppColors

/**
 * Compact toolbar search field (IDE-style): leading magnifier icon, placeholder hint,
 * `surfaceVariant` fill, and a clear button that only appears when text is non-empty.
 *
 * Built from `Surface` + `BasicTextField` rather than `OutlinedTextField` because M2's
 * decoration box reserves vertical label space and clips text when the height is forced
 * below its 56dp minimum; a plain Row centers the content exactly. The border is always
 * drawn (transparent when unfocused) so focusing never shifts the layout.
 *
 * [regex]/[onRegexChange] add an Android-Studio-style `.*` regex toggle inside the field
 * (pass null on screens that don't offer regex matching). When regex is on and the current
 * text does not compile, the border turns error-red — an inline, non-modal signal that
 * the pattern is invalid (matching silently falls back to plain-contains in the core).
 *
 * Dialog form fields keep using `OutlinedTextField` — this is for toolbar/filter-row
 * search only.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
    regex: Boolean? = null,
    onRegexChange: ((Boolean) -> Unit)? = null,
) {
    val hint = MaterialTheme.colors.onSurface.copy(alpha = 0.55f)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val regexOn = regex == true
    val invalidRegex = regexOn && value.isNotEmpty() && runCatching { value.toRegex() }.isFailure
    val borderColor = when {
        invalidRegex -> MaterialTheme.colors.error
        focused -> MaterialTheme.colors.primary
        else -> Color.Transparent
    }
    Surface(
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = AppColors.current.surfaceVariant,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = hint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                interactionSource = interactionSource,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.body2, color = hint, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            if (regex != null && onRegexChange != null) {
                RegexToggle(on = regexOn, onToggle = { onRegexChange(!regexOn) })
                Spacer(Modifier.width(4.dp))
            }
            if (value.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = Strings.t("clear_search"),
                    tint = hint,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onValueChange("") },
                        ),
                )
            }
        }
    }
}

/** Android-Studio-style `.*` regex toggle pill: filled with primary when active, hint-tinted otherwise. */
@Composable
private fun RegexToggle(on: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (on) MaterialTheme.colors.primary else Color.Transparent,
        border = BorderStroke(1.dp, if (on) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.35f)),
    ) {
        Text(
            ".*",
            style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
            color = if (on) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            modifier = Modifier
                .toggleable(
                    value = on,
                    role = Role.Checkbox,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onValueChange = { onToggle() },
                )
                .semantics { contentDescription = Strings.t("regex_match") }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}
