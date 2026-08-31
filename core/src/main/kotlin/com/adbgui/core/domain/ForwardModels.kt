package com.adbgui.core.domain

/** One endpoint of an `adb forward` mapping. Mirrors the adb spec syntax: `<type>:<value>`. */
enum class ForwardEndpointType(val prefix: String) {
    TCP("tcp:"),
    LOCALABSTRACT("localabstract:"),
    LOCALRESERVED("localreserved:"),
    LOCALFILESYSTEM("localfilesystem:");
    companion object {
        /** Parse a single adb endpoint token like `tcp:9222` or `localabstract:foo`.
         *  Returns null for an unrecognized prefix (caller skips the line). */
        fun parse(token: String): ForwardSpec? {
            for (t in entries) {
                if (token.startsWith(t.prefix)) {
                    return ForwardSpec(t, token.removePrefix(t.prefix))
                }
            }
            return null
        }
    }
}

data class ForwardSpec(val type: ForwardEndpointType, val value: String) {
    /** The form adb expects on the command line, e.g. `tcp:9222`. */
    fun adbForm(): String = type.prefix + value
}

/** One row of `adb forward --list`: `<serial> <local> <remote>`. */
data class ForwardEntry(val serial: String, val local: ForwardSpec, val remote: ForwardSpec)
