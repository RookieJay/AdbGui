package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LsParserTest {
    private val out = """
        total 32
        drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos
        -rw-rw---- 1 root root  123 2020-01-01 12:00 test.txt
        drwxr-xr-x 3 root root 4096 2020-01-02 08:30 Music
        -rw-r--r-- 1 root root 4567 2021-05-15 14:22 my notes.txt
    """.trimIndent()

    @Test fun parses_dir_and_file() {
        val list = LsParser.parse(out)
        assertEquals(4, list.size)
        assertEquals("Photos", list[0].name)
        assertTrue(list[0].isDirectory)
        assertEquals(4096, list[0].size)
        assertEquals("2020-01-01 12:00", list[0].date)
        assertEquals("drwxrwx---", list[0].permissions)
    }

    @Test fun file_not_directory() {
        val list = LsParser.parse(out)
        assertEquals("test.txt", list[1].name)
        assertTrue(!list[1].isDirectory)
        assertEquals(123, list[1].size)
    }

    @Test fun preserves_spaces_in_name() {
        val list = LsParser.parse(out)
        assertEquals("my notes.txt", list[3].name)
        assertEquals(4567, list[3].size)
    }

    @Test fun skips_total_and_dot_entries() {
        val list = LsParser.parse("total 0\n")
        assertEquals(0, list.size)
        val list2 = LsParser.parse("drwxrwx--- 2 root root 4096 2020-01-01 12:00 .\ndrwxrwx--- 2 root root 4096 2020-01-01 12:00 ..\n")
        assertEquals(0, list2.size)
    }

    @Test fun parses_symlinks() {
        val out = """
            lrw-r--r-- 1 root root 11 2009-01-01 00:00 etc -> /system/etc
            -rwxr-x--- 1 root shell 1478764 2009-01-01 00:00 init
        """.trimIndent()
        val list = LsParser.parse(out)
        assertEquals(2, list.size)
        assertEquals("etc", list[0].name)
        assertTrue(list[0].isDirectory)   // symlinks start with 'l' — treat as non-file (navigable)
    }
}
