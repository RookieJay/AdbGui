package com.adbgui.core.adb

import com.adbgui.core.domain.FileEntry

object LsParser {
    // drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos
    // lrw-r--r-- 1 root root 50 2020-01-01 12:00 etc -> /system/etc
    private val re = Regex("""^([ldrwxst-]{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})\s+(.+)$""")

    fun parse(stdout: String): List<FileEntry> {
        return stdout.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("total")) return@mapNotNull null
                val m = re.matchEntire(trimmed) ?: return@mapNotNull null
                val perms = m.groupValues[1]
                val name = m.groupValues[5].trim()
                val linkName = name.substringBefore(" -> ")  // strip symlink target (e.g. "etc -> /system/etc" → "etc")
                if (linkName == "." || linkName == "..") return@mapNotNull null
                FileEntry(
                    name = linkName,
                    isDirectory = perms.firstOrNull() == 'd',
                    isSymlink = perms.firstOrNull() == 'l',
                    size = m.groupValues[2].toLongOrNull() ?: 0,
                    date = "${m.groupValues[3]} ${m.groupValues[4]}",
                    permissions = perms,
                    raw = line,
                )
            }
            .toList()
    }
}
