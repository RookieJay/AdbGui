package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.theme.AppColors

/**
 * Reusable surface container that replaces the flat "section + Divider" pattern. Wraps content in
 * an elevated, rounded [Card] with consistent padding so functional blocks read as grouped,
 * raised units instead of a flat list separated by thin lines (which vanish in dark mode).
 *
 * Pass [headerTitle] to render a `subtitle1` header separated from the body by the theme-aware
 * `divider` token (see [AppColors.current.divider]) — not Material's default `Divider`, which is
 * `onSurface.copy(0.12)` and nearly invisible in dark mode.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    headerTitle: String? = null,
    elevation: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val divider = AppColors.current.divider
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        backgroundColor = MaterialTheme.colors.surface,
        elevation = elevation,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (headerTitle != null) {
                Text(headerTitle, style = MaterialTheme.typography.subtitle1)
                Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
            }
            content()
        }
    }
}
