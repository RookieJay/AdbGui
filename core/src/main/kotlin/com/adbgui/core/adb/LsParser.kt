package com.adbgui.core.adb

import com.adbgui.core.domain.FileEntry

object LsParser {
    // Standard layout (ISO or Mon DD date) — link count + size always present:
    //   drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos          (ISO: YYYY-MM-DD HH:MM)
    //   drwxr-xr-x 2 root root 4096 Aug 18 09:48 cache               (Mon DD HH:MM)
    //   -rw-r--r-- 1 root root  123 Aug 18  2024 old.log             (Mon DD  YYYY, old file)
    //   lrw-r--r-- 1 root root 50 2020-01-01 12:00 etc -> /system/etc
    private val re = Regex(
        """^([ldrwxst-]{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+""" +
        """(?:(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})|([A-Z][a-z]{2}\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})))\s+(.+)$"""
    )
    // Non-standard layout (e.g. TCL Android 6.0 default `ls`): NO link-count column, size optional
    // (present for files, absent for symlinks/dirs). Same capture groups as `re` so extraction is shared.
    //   lrwxrwxrwx root root 2007-01-01 20:00 etc -> /system/etc     (symlink, no size)
    //   -rw-r--r-- root root 549 1970-01-01 08:00 default.prop       (file, size present)
    // Tried only when `re` (strict) doesn't match — standard-layout lines never reach here.
    private val reTcl = Regex(
        """^([ldrwxst-]{10})\s+\S+\s+\S+\s+(?:(\d+)\s+)?""" +
        """(?:(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})|([A-Z][a-z]{2}\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})))\s+(.+)$"""
    )

    fun parse(stdout: String): List<FileEntry> {
        return stdout.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("total")) return@mapNotNull null
                val m = re.matchEntire(trimmed) ?: reTcl.matchEntire(trimmed) ?: return@mapNotNull null
                val perms = m.groupValues[1]
                val date = m.groupValues[3].ifBlank { m.groupValues[4] }  // ISO group, else Mon group
                val name = m.groupValues[5].trim()
                val linkName = name.substringBefore(" -> ")  // strip symlink target (e.g. "etc -> /system/etc" → "etc")
                if (linkName == "." || linkName == "..") return@mapNotNull null
                FileEntry(
                    name = linkName,
                    isDirectory = perms.firstOrNull() == 'd',
                    isSymlink = perms.firstOrNull() == 'l',
                    size = m.groupValues[2].toLongOrNull() ?: 0,
                    date = date,
                    permissions = perms,
                    raw = line,
                )
            }
            .toList()
    }
}
