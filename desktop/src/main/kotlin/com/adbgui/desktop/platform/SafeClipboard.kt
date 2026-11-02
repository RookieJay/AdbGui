package com.adbgui.desktop.platform

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection

/**
 * All clipboard writes go through here.
 *
 * The Windows clipboard is shared and briefly locked by other processes (clipboard managers,
 * IMEs, chat-app screenshot tools). While locked, AWT throws
 * `IllegalStateException("cannot open system clipboard")`. Left unguarded, that exception
 * escaped the UI event handler and killed the app (2026-10-13). Contention is usually
 * momentary, so retry briefly; if still locked, give up and report failure — a missed copy
 * beats a crash.
 */
object SafeClipboard {
    internal const val ATTEMPTS = 3
    internal const val RETRY_DELAY_MS = 50L

    /** True if the text reached the clipboard. Retries [IllegalStateException] (clipboard busy). */
    fun copyText(text: String, clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard): Boolean =
        retry(defaultValue = false) { clipboard.setContents(StringSelection(text), null); true }

    /**
     * Wraps Compose's clipboard manager for [androidx.compose.ui.platform.LocalClipboardManager]:
     * guards Compose's own copy path (SelectableText/SelectionContainer Ctrl+C), which bypasses
     * [copyText]. Reads fall back to empty rather than throwing while the clipboard is locked.
     */
    fun wrapManager(delegate: ClipboardManager): ClipboardManager = SafeClipboardManager(delegate)
}

internal class SafeClipboardManager(private val delegate: ClipboardManager) : ClipboardManager {
    override fun setText(text: AnnotatedString) {
        retry(defaultValue = Unit) { delegate.setText(text) }
    }

    override fun getText(): AnnotatedString? = retry(defaultValue = null) { delegate.getText() }

    override fun hasText(): Boolean = retry(defaultValue = false) { delegate.hasText() }
}

/**
 * Runs [operation] up to [SafeClipboard.ATTEMPTS] times while it fails with [IllegalStateException]
 * (clipboard locked by another process); returns [defaultValue] if it never succeeds. Other
 * exceptions propagate — they indicate real bugs and belong to [CrashGuard] logging, not here.
 */
private inline fun <T> retry(defaultValue: T, operation: () -> T): T {
    repeat(SafeClipboard.ATTEMPTS - 1) {
        try {
            return operation()
        } catch (_: IllegalStateException) {
            Thread.sleep(SafeClipboard.RETRY_DELAY_MS)
        }
    }
    return try {
        operation()
    } catch (_: IllegalStateException) {
        defaultValue
    }
}
