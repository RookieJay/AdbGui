package com.adbgui.desktop.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
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
import com.adbgui.desktop.ui.InlineMessageBanner
import com.adbgui.desktop.ui.MessageKind
import com.adbgui.desktop.ui.SettingsViewModel
import com.adbgui.desktop.ui.i18n.Strings

/**
 * Prominent inline banner in the main window that surfaces the whole update flow:
 * Available (download offer) → Downloading (progress + cancel) → Ready (install) →
 * Installing (caption) → Error (message + collapsible raw). Keeping every in-flight state
 * visible here means the user always sees what's happening (no silent disappearance mid-flow).
 *
 * Available still respects per-version dismiss (settings.update.dismissedVersion); once the user
 * commits by clicking download, the banner stays through Downloading/Ready/Installing.
 */
@Composable
fun UpdateBanner(
    updateVm: UpdateViewModel,
    settingsVm: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by updateVm.state.collectAsState()
    val settings by settingsVm.settings.collectAsState()

    // Available respects per-version dismiss; in-flight states (Downloading/Ready/Installing)
    // and Error show regardless so the user has continuous feedback once they've acted.
    val available = state as? UpdateState.Available
    if (available != null) {
        val dismissed = settings.update.dismissedVersion
        if (dismissed == available.manifest.version) return
    }

    // Hide states that have nothing actionable to show in the banner.
    when (state) {
        is UpdateState.Available, is UpdateState.Downloading,
        is UpdateState.Ready, is UpdateState.Installing, is UpdateState.Error -> Unit
        else -> return
    }

    // Opaque theme-aware tint: lerp(surface, primary, 0.12) inverts with the theme (dark in dark
    // mode, light in light mode) — avoids transparent primary over the window background.
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
            when (val s = state) {
                is UpdateState.Available -> {
                    val m = s.manifest
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
                is UpdateState.Downloading -> {
                    val pct = if (s.progress < 0f) null else (s.progress * 100).toInt()
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (pct != null) Strings.t("update_downloading").format(pct)
                            else Strings.t("update_downloading_indeterminate"),
                            style = MaterialTheme.typography.subtitle2,
                        )
                        if (s.progress < 0f) LinearProgressIndicator()
                        else LinearProgressIndicator(progress = s.progress)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { updateVm.cancelDownload() }) {
                        Text(Strings.t("update_cancel_download"))
                    }
                }
                is UpdateState.Ready -> {
                    val m = s.manifest
                    Text(
                        Strings.t("update_ready_to_install").format(m.version),
                        style = MaterialTheme.typography.subtitle2,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { updateVm.installNow() }) {
                        Text(Strings.t("update_install_now"))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { updateVm.openDownloadPage() }) {
                        Text(Strings.t("update_open_page"))
                    }
                }
                is UpdateState.Installing -> {
                    Text(
                        Strings.t("update_installing"),
                        style = MaterialTheme.typography.subtitle2,
                        modifier = Modifier.weight(1f),
                    )
                }
                is UpdateState.Error -> {
                    // Reuse the collapsible error banner so the raw (e.g. bad manifest text) is
                    // available but not dominant — same pattern as adb command errors in settings.
                    InlineMessageBanner(
                        text = s.message,
                        kind = MessageKind.Error,
                        details = s.raw,
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> Unit
            }
        }
    }
}
