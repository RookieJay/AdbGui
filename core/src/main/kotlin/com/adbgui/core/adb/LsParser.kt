package com.adbgui.core.adb

import com.adbgui.core.domain.FileEntry

object LsParser {
    // drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos          (ISO: YYYY-MM-DD HH:MM)
    // drwxr-xr-x 2 root root 4096 Aug 18 09:48 cache               (Mon DD HH:MM)
    // -rw-r--r-- 1 root root  123 Aug 18  2024 old.log             (Mon DD  YYYY, old file)
    // lrw-r--r-- 1 root root 50 2020-01-01 12:00 etc -> /system/etc
    // Date = one group, ISO (2 tokens) or Mon DD (3 tokens); captured verbatim into FileEntry.date.
    private val re = Regex(
        """^([ldrwxst-]{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+""" +
        """(?:(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})|([A-Z][a-z]{2}\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})))\s+(.+)$"""
    )

    fun parse(stdout: String): List<FileEntry> {
        return stdout.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("total")) return@mapNotNull null
                val m = re.matchEntire(trimmed) ?: return@mapNotNull null
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
