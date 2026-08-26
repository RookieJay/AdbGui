package com.adbgui.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.theme.AdbGuiTheme

/**
 * Independent OS window hosting [ScreenshotScreen]. Opened on demand from Device Overview
 * (like scrcpy's external window) so the screenshot preview does not occupy the overview page.
 * One-shot: closing the window discards the in-session capture state.
 *
 * Must be rendered under the [androidx.compose.ui.window.ApplicationScope] provided by `application`.
 * Wraps in [AdbGuiTheme] with the given [themeCode] so the screenshot window's dark/light mode
 * matches the main window (it has its own top-level Window, so it would otherwise stay default light).
 */
@Composable
fun ScreenshotWindow(vm: ScreenshotViewModel, themeCode: String, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = Strings.t("screenshot"),
    ) {
        AdbGuiTheme(themeCode) {
            ScreenshotScreen(vm = vm)
        }
    }
}
