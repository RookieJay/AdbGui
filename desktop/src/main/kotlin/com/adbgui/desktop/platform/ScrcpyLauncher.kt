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
    /** Launch scrcpy against [serial] with [options]. In [ScrcpyMode.EMBEDDED], an AWT [Canvas] is created and the scrcpy SDL window is reparented into it.
     *  [onExit] is invoked with the process exit code and a tail of scrcpy's merged stdout/stderr when the scrcpy process exits on its own (user closed the SDL window, or scrcpy failed). The UI uses it to clear its "running" state and surface failures (non-zero exit). It is NOT invoked for a process stopped via [stop] (the caller already knows). */
    fun open(scrcpyPath: String, serial: String, options: ScrcpyOptions, mode: ScrcpyMode, onExit: (exitCode: Int, output: String) -> Unit = { _, _ -> })
    fun isRunning(): Boolean
    fun stop()
    /** The canvas used for embedding, or null when [open] was called with [ScrcpyMode.EXTERNAL] or no process is running. The UI is responsible for adding it to a displayable window before [open] is called for embedding to succeed. */
    fun embeddedCanvas(): Canvas?
}

class WindowsScrcpyLauncher : ScrcpyLauncher {
    private val processRef = AtomicReference<Process?>(null)
    private val canvasRef = AtomicReference<Canvas?>(null)

    override fun open(scrcpyPath: String, serial: String, options: ScrcpyOptions, mode: ScrcpyMode, onExit: (exitCode: Int, output: String) -> Unit) {
        stop()
        val args = ScrcpyArgsBuilder.build(scrcpyPath, serial, options)
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        processRef.set(proc)
        // Drain scrcpy's merged stdout/stderr so it can't block on a full pipe, and keep a
        // tail for error reporting on exit (scrcpy writes diagnostics, e.g. "Encoder failed").
        val output = StringBuilder()
        Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        synchronized(output) {
                            output.append(line).append('\n')
                            if (output.length > 4000) output.delete(0, output.length - 4000)
                        }
                    }
                }
            } catch (_: Throwable) { /* reader ends with the process */ }
        }.also { it.isDaemon = true; it.start() }
        // Notify the UI when scrcpy exits on its own (window closed / failure). Guard by
        // identity so a stale process (replaced by a new open(), or cleared by stop()) does
        // not wrongly clear the current session's running state.
        proc.onExit().thenAccept {
            if (processRef.get() === proc) {
                processRef.set(null)
                val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                val out = synchronized(output) { output.toString() }.trim()
                onExit(code, out)
            }
        }
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
                    val scrcpyHwnd = findScrcpyWindow(proc.pid()) ?: return@Thread
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
        val proc = processRef.getAndSet(null) ?: return
        canvasRef.set(null)
        // Graceful stop: post WM_CLOSE to the scrcpy SDL window so scrcpy exits its
        // main loop cleanly and finalizes any in-progress --record mp4 (writes the moov
        // atom). Process.destroy()/destroyForcibly() on Windows both force-kill
        // (TerminateProcess), which would corrupt an mp4 being recorded. We wait up
        // to 3s for a clean exit, then force-kill as a fallback. Runs off the UI thread.
        Thread {
            try {
                val hwnd = findScrcpyWindow(proc.pid())
                if (hwnd != null) {
                    User32.INSTANCE.PostMessage(hwnd, WinUser.WM_CLOSE, WinDef.WPARAM(0), WinDef.LPARAM(0))
                    val deadline = System.currentTimeMillis() + 3000
                    while (proc.isAlive && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100)
                    }
                }
                if (proc.isAlive) proc.destroyForcibly()
            } catch (_: Throwable) {
                if (proc.isAlive) proc.destroyForcibly()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun embeddedCanvas(): Canvas? = canvasRef.get()

    /** Finds the visible top-level window belonging to [pid] (the scrcpy SDL window).
     * Matched by process id, not title — scrcpy's window title is the device model,
     * not the serial, so title-based matching would miss it. */
    private fun findScrcpyWindow(pid: Long): WinDef.HWND? {
        val user32 = User32.INSTANCE
        var found: WinDef.HWND? = null
        val pidRef = com.sun.jna.ptr.IntByReference()
        val proc = object : WinUser.WNDENUMPROC {
            override fun callback(hWnd: WinDef.HWND, data: Pointer?): Boolean {
                if (found != null) return false
                user32.GetWindowThreadProcessId(hWnd, pidRef)
                if (pidRef.value.toLong() == pid && user32.IsWindowVisible(hWnd)) {
                    found = hWnd
                    return false
                }
                return true
            }
        }
        user32.EnumWindows(proc, null)
        return found
    }
}
