package com.adbgui.desktop.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import com.adbgui.desktop.ui.i18n.Strings

/**
 * Independent OS window hosting [ScreenshotScreen]. Opened on demand from Device Overview
 * (like scrcpy's external window) so the screenshot preview does not occupy the overview page.
 * One-shot: closing the window discards the in-session capture state.
 *
 * Must be rendered under the [androidx.compose.ui.window.ApplicationScope] provided by `application`.
 */
@Composable
fun ScreenshotWindow(vm: ScreenshotViewModel, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = Strings.t("screenshot"),
    ) {
        MaterialTheme {
            ScreenshotScreen(vm = vm)
        }
    }
}
