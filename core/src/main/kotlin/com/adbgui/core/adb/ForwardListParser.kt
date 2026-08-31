package com.adbgui.core.adb

import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardEntry

/** Parses `adb forward --list` stdout into [ForwardEntry]s. Pure function — no adb, no I/O.
 *  - skips blank lines, `#` comment lines (fixture provenance header), and malformed lines
 *    (lines that don't tokenize into 3 parts or whose endpoints don't use a known prefix);
 *  - never throws: a real `--list` is host-wide and may include rows for devices the caller
 *    doesn't care about; filtering by serial is the caller's job (R4). */
object ForwardListParser {
    private val WS = Regex("\\s+")

    fun parse(stdout: String): List<ForwardEntry> {
        val out = mutableListOf<ForwardEntry>()
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(WS)
            if (parts.size < 3) continue
            val serial = parts[0]
            val local = ForwardEndpointType.parse(parts[1]) ?: continue
            val remote = ForwardEndpointType.parse(parts[2]) ?: continue
            out.add(ForwardEntry(serial, local, remote))
        }
        return out
    }
}
