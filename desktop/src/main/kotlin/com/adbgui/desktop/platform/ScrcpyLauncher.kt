package com.adbgui.desktop.platform

import com.adbgui.core.domain.ScrcpyMode
import com.adbgui.core.domain.ScrcpyOptions
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Canvas
import java.util.concurrent.atomic.AtomicReference

interface ScrcpyLauncher {
    /** Launch scrcpy against [serial] with [options]. In [ScrcpyMode.EMBEDDED], an AWT [Canvas] is created and the scrcpy SDL window is reparented into it. */
    fun open(scrcpyPath: String, serial: String, options: ScrcpyOptions, mode: ScrcpyMode)
    fun isRunning(): Boolean
    fun stop()
    /** The canvas used for embedding, or null when [open] was called with [ScrcpyMode.EXTERNAL] or no process is running. The UI is responsible for adding it to a displayable window before [open] is called for embedding to succeed. */
    fun embeddedCanvas(): Canvas?
}

class WindowsScrcpyLauncher : ScrcpyLauncher {
    private val processRef = AtomicReference<Process?>(null)
    private val canvasRef = AtomicReference<Canvas?>(null)

    override fun open(scrcpyPath: String, serial: String, options: ScrcpyOptions, mode: ScrcpyMode) {
        stop()
        val args = ScrcpyArgsBuilder.build(scrcpyPath, serial, options)
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        processRef.set(proc)
        if (mode == ScrcpyMode.EMBEDDED) {
            val canvas = Canvas()
            canvasRef.set(canvas)
            // Reparent the scrcpy SDL window into our canvas via Win32 SetParent.
            // Best-effort: if the canvas isn't displayable yet, or the SDL window hasn't
            // appeared, the embed silently fails — caller should ensure the canvas is
            // mounted in a shown window before/after calling open().
            Thread {
                try {
                    Thread.sleep(2000) // wait for the SDL window to appear
                    val scrcpyHwnd = findScrcpyWindow(serial) ?: return@Thread
                    // Native.getComponentID returns the canvas HWND; requires the canvas
                    // to be added to a heavyweight peer (displayable window).
                    val canvasHwndLong = Native.getComponentID(canvas)
                    if (canvasHwndLong == 0L) return@Thread
                    val canvasHwnd = WinDef.HWND(Pointer(canvasHwndLong))
                    User32.INSTANCE.SetParent(scrcpyHwnd, canvasHwnd)
                } catch (_: Throwable) {
                    // Embedding failure must not kill the launched process; the user can
                    // still see the external SDL window.
                }
            }.also { it.isDaemon = true; it.start() }
        }
    }

    override fun isRunning(): Boolean {
        val p = processRef.get() ?: return false
        return p.isAlive
    }

    override fun stop() {
        processRef.getAndSet(null)?.destroyForcibly()
        canvasRef.set(null)
    }

    override fun embeddedCanvas(): Canvas? = canvasRef.get()

    private fun findScrcpyWindow(serial: String): WinDef.HWND? {
        val user32 = User32.INSTANCE
        var found: WinDef.HWND? = null
        val proc = object : WinUser.WNDENUMPROC {
            override fun callback(hWnd: WinDef.HWND, data: Pointer?): Boolean {
                if (found != null) return false
                val buf = CharArray(256)
                val len = user32.GetWindowText(hWnd, buf, 256)
                if (len > 0) {
                    val title = String(buf, 0, len)
                    if (title.contains("scrcpy", ignoreCase = true) && title.contains(serial)) {
                        found = hWnd
                        return false
                    }
                }
                return true
            }
        }
        user32.EnumWindows(proc, null)
        return found
    }
}
