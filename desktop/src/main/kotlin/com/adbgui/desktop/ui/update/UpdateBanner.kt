package com.adbgui.desktop.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.adbgui.desktop.ui.SettingsViewModel
import com.adbgui.desktop.ui.i18n.Strings

/**
 * Inline dismissible banner shown in the main window when an update is available.
 *
 * Shown only when [UpdateViewModel.state] is [UpdateState.Available] and the user has not
 * dismissed this specific [version][UpdateManifest.version] (settings.update.dismissedVersion).
 * Other states (Downloading/Ready/Installing) are settings-page-only to keep the main window
 * quiet during install flows.
 */
@Composable
fun UpdateBanner(
    updateVm: UpdateViewModel,
    settingsVm: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by updateVm.state.collectAsState()
    val settings by settingsVm.settings.collectAsState()

    val available = state as? UpdateState.Available
    val dismissed = settings.update.dismissedVersion
    if (available == null || dismissed == available.manifest.version) return

    val m = available.manifest
    // Opaque theme-aware tint: lerp(surface, primary, 0.12) gives a subtle primary wash that
    // inverts with the theme (dark in dark mode, light in light mode) — avoids the transparent
    // primary over the unthemed window background that made the banner read as glaring white.
    val bannerBg = lerp(MaterialTheme.colors.surface, MaterialTheme.colors.primary, 0.12f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bannerBg,
        contentColor = MaterialTheme.colors.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    Strings.t("update_banner_title").format(m.version),
                    style = MaterialTheme.typography.subtitle2,
                )
                m.notes?.let { notes ->
                    Text(
                        notes,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                    )
                }
            }
            Button(onClick = { updateVm.downloadUpdate() }) {
                Text(Strings.t("update_download_install"))
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { updateVm.openDownloadPage() }) {
                Text(Strings.t("update_open_page"))
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { updateVm.dismissCurrentUpdate() }) {
                Text(Strings.t("update_dismiss"))
            }
        }
    }
}
