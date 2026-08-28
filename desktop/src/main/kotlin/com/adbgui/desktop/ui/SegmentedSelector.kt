package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.theme.AppColors

/**
 * Inline segmented selector: a single-choice row of options in an outlined pill. The selected
 * option is filled with the primary color (on-primary text); the rest are transparent with
 * on-surface text. Replaces the ad-hoc "row of TextButtons, selected = colored" pattern used for
 * language / theme / log-level in Settings, which read as a row of links rather than a control.
 *
 * Generic over [T]; [optionLabel] is composable so it can call [Strings.t] for localized labels.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    val outline = AppColors.current.divider
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, outline),
    ) {
        Row {
            options.forEachIndexed { index, option ->
                val isSelected = option == selected
                val bg = if (isSelected) MaterialTheme.colors.primary else Color.Transparent
                val fg = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(bg)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { if (!isSelected) onSelect(option) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        optionLabel(option),
                        color = fg,
                        style = MaterialTheme.typography.body2,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
