package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.i18n.Strings

/**
 * Info banner showing a saved file's path with Open / Reveal-in-folder actions.
 * Replaces the ad-hoc `Surface(background) { Row { Text(saved_path) + TextButtons } }` pattern
 * that was duplicated across Logcat / FileExplorer / Screenshot / DeviceInfo / DeviceOverview
 * with slight shape/padding/SelectableText variations. The path is [SelectableText] so a long
 * path can be copied.
 */
@Composable
fun SavedFileBanner(
    path: String,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    openLabel: String = Strings.t("open"),
) {
    Surface(
        color = MaterialTheme.colors.background,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            SelectableText(Strings.t("saved_path").format(path), style = MaterialTheme.typography.caption)
            Row {
                TextButton(onClick = onOpen) { Text(openLabel) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onReveal) { Text(Strings.t("open_folder")) }
            }
        }
    }
}
