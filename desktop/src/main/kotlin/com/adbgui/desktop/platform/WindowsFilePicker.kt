package com.adbgui.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.W32APIOptions

/**
 * Modern native Windows file-open dialog (Vista+ `IFileOpenDialog`) — the real Explorer window with
 * a breadcrumb address bar at the top. Click the breadcrumb's empty area (or the current folder name
 * in it) and it turns into an editable field where you can type or paste a full path.
 *
 * We need this because AWT's `java.awt.FileDialog` renders the OLD common dialog (`GetOpenFileName`)
 * even on modern Windows — a "Look in:" dropdown with no typeable address bar. There is no Java API
 * to force the modern dialog, so the only route is direct COM interop with `IFileOpenDialog`.
 *
 * ### COM interop approach
 * Hand-written vtable dispatch (jna-platform's COM helpers are IDispatch-oriented; IFileOpenDialog
 * is a raw vtable interface). The object pointer's first field is the vtable (array of function
 * pointers); we index by slot and invoke via `Function`. Slots are reconstructed from the
 * inheritance chain `IFileOpenDialog : IFileDialog : IModalWindow : IUnknown` (IUnknown=0,1,2;
 * IModalWindow::Show=3; IFileDialog=4..25; IFileOpenDialog=26..28) and cross-checked against a
 * second source — but they are NOT verified against the SDK header (not present on this machine,
 * reference sites are network-blocked). So every HRESULT is logged and any failure throws, caught
 * by `FileDialogs.pickFile` which falls back to the legacy AWT FileDialog — the app never breaks.
 * Worst case the user sees the old dialog (same as before) + a log line to diagnose.
 *
 * Bypassing the IFileOpenDialog IID risk: `CoCreateInstance` is called with `IID_IUnknown` (every
 * COM object supports it → guaranteed success). For a chain interface like FileOpenDialog the
 * IUnknown pointer shares the IFileOpenDialog vtable, so by-offset dispatch works.
 */
internal object WindowsFilePicker {
    private val ole32 = Native.load("ole32", Ole32::class.java, W32APIOptions.DEFAULT_OPTIONS)!!
    private val shell32 = Native.load("shell32", Shell32Extra::class.java, W32APIOptions.DEFAULT_OPTIONS)!!

    /** Shell32 functions jna-platform's Shell32 doesn't declare. */
    private interface Shell32Extra : Library {
        // HRESULT SHCreateItemFromParsingName(PCWSTR pszPath, IBindCtx *pbc, REFIID riid, void **ppv)
        fun SHCreateItemFromParsingName(pszPath: WString, pbc: Pointer?, riid: Guid.GUID, ppv: PointerByReference): WinNT.HRESULT
    }

    // CLSID_FileOpenDialog {DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7} (verified via HKCR\CLSID registry
    // enumeration on Win11 — "File Open Dialog", InprocServer32 = shell32.dll). Stable across Vista–Win11.
    private val CLSID_FILE_OPEN_DIALOG = Guid.GUID("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")
    // IID_IUnknown {00000000-0000-0000-C000-000000000046}
    private val IID_IUNKNOWN = Guid.GUID("00000000-0000-0000-C000-000000000046")

    // IFileOpenDialog vtable slots (reconstructed; see class doc for the inheritance breakdown).
    private const val SLOT_SHOW = 3
    private const val SLOT_SET_OPTIONS = 9
    private const val SLOT_GET_OPTIONS = 10
    private const val SLOT_SET_DEFAULT_FOLDER = 11
    private const val SLOT_SET_FOLDER = 12
    private const val SLOT_SET_TITLE = 17
    private const val SLOT_GET_RESULT = 26
    // IShellItem vtable slots (IUnknown=0,1,2; BindToHandler=3; GetParent=4; GetDisplayName=5)
    private const val SLOT_SHELLITEM_GET_DISPLAY_NAME = 5

    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val S_OK = 0
    private const val ERROR_CANCELLED_HRESULT = 0x800704C7L // HRESULT_FROM_WIN32(1223 / ERROR_CANCELLED)
    private const val FOS_NOCHANGEDIR = 0x00000008
    private const val FOS_FILEMUSTEXIST = 0x00001000
    private const val FOS_PATHMUSTEXIST = 0x00000800
    private const val SIGDN_FILESYSPATH = 0x80058000

    /**
     * Open the modern file dialog. Returns the chosen absolute path, or null if cancelled / the
     * modern dialog isn't usable (caller falls back). Throws on COM failure so the caller's
     * try/catch can route to the legacy picker + log.
     */
    fun pickFile(title: String, currentPath: String?): String? {
        var pUnk: Pointer? = null
        var pFolderItem: Pointer? = null
        var pResultItem: Pointer? = null
        var pPathW: Pointer? = null
        try {
            // CoInitializeEx may return S_FALSE/RPC_E_CHANGED_MODE if already init in another mode;
            // ignore — we only need COM usable for the duration of the dialog.
            runCatching { ole32.CoInitializeEx(null, COINIT_APARTMENTTHREADED) }

            val ppv = PointerByReference()
            val hr = ole32.CoCreateInstance(CLSID_FILE_OPEN_DIALOG, null, CLSCTX_INPROC_SERVER, IID_IUNKNOWN, ppv)
            if (hr.toInt() != S_OK) error("CoCreateInstance hr=0x${hr.toInt().toString(16)}")
            pUnk = ppv.value ?: error("CoCreateInstance returned null")

            // Set options: add NOCHANGEDIR | FILEMUSTEXIST | PATHMUSTEXIST to whatever's current.
            val opts = IntByReference()
            checkHr(pUnk, SLOT_GET_OPTIONS, invokeHr(pUnk, SLOT_GET_OPTIONS, arrayOf(opts)), "GetOptions")
            val newOpts = opts.value or FOS_NOCHANGEDIR or FOS_FILEMUSTEXIST or FOS_PATHMUSTEXIST
            checkHr(pUnk, SLOT_SET_OPTIONS, invokeHr(pUnk, SLOT_SET_OPTIONS, arrayOf(WinDef.DWORD(newOpts.toLong()))), "SetOptions")

            // Title.
            checkHr(pUnk, SLOT_SET_TITLE, invokeHr(pUnk, SLOT_SET_TITLE, arrayOf(WString(title))), "SetTitle")

            // Auto-locate: build an IShellItem for the current path's directory and SetFolder.
            // Non-fatal — if it fails we just open at the OS default.
            runCatching { setFolder(pUnk!!, currentPath) }.onFailure { log("SetFolder skipped: ${it.message}") }

            // Show (hwndOwner = null). User cancelling → HRESULT_FROM_WIN32(ERROR_CANCELLED).
            val showHr = invokeHr(pUnk, SLOT_SHOW, arrayOf<Any?>(null))
            val showHrLong = showHr.toInt().toLong() and 0xFFFFFFFFL  // unsigned
            if (showHrLong == ERROR_CANCELLED_HRESULT) return null  // user cancelled
            if (showHrLong != 0L) error("Show hr=0x${showHrLong.toString(16)}")

            // GetResult → IShellItem* for the chosen item.
            val resultRef = PointerByReference()
            checkHr(pUnk, SLOT_GET_RESULT, invokeHr(pUnk, SLOT_GET_RESULT, arrayOf(resultRef)), "GetResult")
            pResultItem = resultRef.value ?: return null

            // IShellItem::GetDisplayName(SIGDN_FILESYSPATH) → PWSTR (COM-allocated, CoTaskMemFree).
            val pathRef = PointerByReference()
            checkHr(pResultItem, SLOT_SHELLITEM_GET_DISPLAY_NAME,
                invokeHr(pResultItem, SLOT_SHELLITEM_GET_DISPLAY_NAME, arrayOf(WinDef.DWORD(SIGDN_FILESYSPATH.toLong()), pathRef)),
                "GetDisplayName")
            pPathW = pathRef.value ?: return null
            return pPathW.getWideString(0)
        } finally {
            pPathW?.let { ole32.CoTaskMemFree(it) }
            pResultItem?.let { release(it) }
            pFolderItem?.let { release(it) }
            pUnk?.let { release(it) }
        }
    }

    /** Build an IShellItem for currentPath's directory and pass it to SetFolder. Throws on failure. */
    private fun setFolder(pUnk: Pointer, currentPath: String?) {
        val dir = parentDirOf(currentPath) ?: return
        val itemRef = PointerByReference()
        val hr = shell32.SHCreateItemFromParsingName(WString(dir), null, IID_IUNKNOWN, itemRef)
        if (hr.toInt() != S_OK) error("SHCreateItemFromParsingName hr=0x${hr.toInt().toString(16)} dir=$dir")
        val item = itemRef.value ?: return
        checkHr(pUnk, SLOT_SET_FOLDER, invokeHr(pUnk, SLOT_SET_FOLDER, arrayOf(item)), "SetFolder")
    }

    private fun parentDirOf(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val f = java.io.File(path)
        val dir = when {
            f.isDirectory -> f
            f.parentFile != null -> f.parentFile
            else -> return null
        }
        return dir.absolutePath
    }

    // --- COM vtable dispatch helpers ---

    /** Read the object's vtable pointer, index to [slot], wrap as a Function, invoke returning the HRESULT (int). */
    private fun invokeHr(pObj: Pointer, slot: Int, args: Array<Any?>): WinNT.HRESULT {
        val fn = vtableFunction(pObj, slot)
        val callArgs: Array<Any?> = arrayOf<Any?>(pObj, *args)  // `this` pointer is always arg 0
        // Function.invoke(Class, Object[]) returns the boxed result; avoids the invokeInt overload
        // ambiguity Kotlin hits when passing an Array<Any?>.
        val hrInt = (fn.invoke(java.lang.Integer.TYPE, callArgs) as Number).toInt()
        return WinNT.HRESULT(hrInt)
    }

    /** Get the JNA Function for [slot] of [pObj]'s vtable. */
    private fun vtableFunction(pObj: Pointer, slot: Int): Function {
        val vtable = pObj.getPointer(0) ?: error("null vtable at slot $slot")
        val fnPtr = vtable.getPointer(slot.toLong() * Native.POINTER_SIZE.toLong())
            ?: error("null function pointer at slot $slot")
        return Function.getFunction(fnPtr)
    }

    private fun checkHr(pObj: Pointer, slot: Int, hr: WinNT.HRESULT, name: String) {
        if (hr.toInt() != S_OK) error("$name hr=0x${hr.toInt().toString(16)}")
    }

    private fun release(pObj: Pointer) {
        runCatching {
            val fn = vtableFunction(pObj, 2)
            fn.invoke(java.lang.Integer.TYPE, arrayOf<Any?>(pObj))  // IUnknown::Release slot 2
        }
    }

    private fun log(msg: String) {
        // Visible in the `:desktop:run` gradle task output for diagnosis; harmless in production.
        System.err.println("[WindowsFilePicker] $msg")
    }
}
