package com.adbgui.desktop.platform

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the 2026-10-13 crash: while another process held the Windows clipboard,
 * AWT threw IllegalStateException("cannot open system clipboard") which escaped the UI event
 * handler and killed the app. All clipboard writes must retry transient locks and swallow
 * persistent ones instead of throwing.
 */
class SafeClipboardTest {

    private class FakeClipboard : Clipboard("test") {
        /** Times setContents throws "cannot open system clipboard" before succeeding. */
        var busyTimes = 0
        var setCalls = 0
        var lastContents: Transferable? = null

        override fun setContents(contents: Transferable?, owner: ClipboardOwner?) {
            setCalls++
            lastContents = contents
            if (busyTimes > 0) {
                busyTimes--
                throw IllegalStateException("cannot open system clipboard")
            }
        }
    }

    private class FakeManager : ClipboardManager {
        var busyTimes = 0
        var setCalls = 0
        var getThrows = false
        var lastText: AnnotatedString? = null

        override fun setText(text: AnnotatedString) {
            setCalls++
            lastText = text
            if (busyTimes > 0) {
                busyTimes--
                throw IllegalStateException("cannot open system clipboard")
            }
        }

        override fun getText(): AnnotatedString? {
            if (getThrows) throw IllegalStateException("cannot open system clipboard")
            return AnnotatedString("clip-text")
        }
    }

    // ---- SafeClipboard.copyText (app copy buttons) ----

    @Test
    fun `copyText writes clipboard when unlocked`() {
        val clip = FakeClipboard()
        val ok = SafeClipboard.copyText("hello", clip)
        assertTrue(ok)
        assertEquals(1, clip.setCalls)
        assertEquals("hello", (clip.lastContents as? StringSelection)?.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor))
    }

    @Test
    fun `copyText retries transient clipboard lock and succeeds`() {
        val clip = FakeClipboard().apply { busyTimes = 2 }
        val ok = SafeClipboard.copyText("hello", clip)
        assertTrue(ok)
        assertEquals(3, clip.setCalls)
    }

    @Test
    fun `copyText gives up quietly when clipboard stays locked`() {
        val clip = FakeClipboard().apply { busyTimes = Int.MAX_VALUE }
        val ok = SafeClipboard.copyText("hello", clip)
        assertFalse(ok)
        assertEquals(3, clip.setCalls) // bounded attempts, not an infinite loop
    }

    @Test
    fun `copyText propagates non-contention exceptions`() {
        val clip = object : Clipboard("boom") {
            override fun setContents(contents: Transferable?, owner: ClipboardOwner?) =
                throw RuntimeException("real bug")
        }
        assertFailsWith<RuntimeException> { SafeClipboard.copyText("hello", clip) }
    }

    // ---- SafeClipboardManager (Compose Ctrl+C path via LocalClipboardManager) ----

    @Test
    fun `manager setText retries transient lock and passes text through`() {
        val delegate = FakeManager().apply { busyTimes = 1 }
        val manager = SafeClipboardManager(delegate)
        manager.setText(AnnotatedString("copied"))
        assertEquals(2, delegate.setCalls)
        assertEquals("copied", delegate.lastText?.text)
    }

    @Test
    fun `manager setText swallows persistent lock instead of crashing`() {
        val delegate = FakeManager().apply { busyTimes = Int.MAX_VALUE }
        val manager = SafeClipboardManager(delegate)
        manager.setText(AnnotatedString("copied")) // must not throw
        assertEquals(3, delegate.setCalls)
    }

    @Test
    fun `manager getText falls back to null when clipboard locked`() {
        val manager = SafeClipboardManager(FakeManager().apply { getThrows = true })
        assertNull(manager.getText())
    }
    @Test
    fun `manager getText passes through when readable`() {
        val manager = SafeClipboardManager(FakeManager())
        assertEquals("clip-text", manager.getText()?.text)
    }

    @Test
    fun `manager hasText falls back to false when clipboard locked`() {
        val manager = SafeClipboardManager(FakeManager().apply { getThrows = true })
        assertFalse(manager.hasText())
    }

    @Test
    fun `manager hasText passes through when readable`() {
        class NonEmpty : ClipboardManager {
            override fun setText(text: AnnotatedString) = Unit
            override fun getText(): AnnotatedString? = AnnotatedString("x")
        }
        assertTrue(SafeClipboardManager(NonEmpty()).hasText())
    }
}
