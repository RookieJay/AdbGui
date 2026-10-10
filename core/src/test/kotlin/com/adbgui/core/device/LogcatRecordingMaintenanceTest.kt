package com.adbgui.core.device

import com.adbgui.core.log.NoopLogger
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogcatRecordingMaintenanceTest {
    private val now = System.currentTimeMillis()
    private val m = LogcatRecordingMaintenance(NoopLogger, clock = { now })

    private fun dir(): java.nio.file.Path = Files.createTempDirectory("adbgui-mnt").also {
        Files.createDirectories(it.resolve("auto"))
        Files.createDirectories(it.resolve("manual"))
    }

    @Test fun reorganize_moves_legacy_root_files_to_subdirs() {
        val d = dir()
        d.resolve("auto_legacy.log").writeText("x")
        d.resolve("manual_legacy.log").writeText("x")
        m.reorganizeLegacyFiles(d)
        assertTrue(Files.exists(d.resolve("auto").resolve("auto_legacy.log")))
        assertTrue(Files.exists(d.resolve("manual").resolve("manual_legacy.log")))
        assertFalse(Files.exists(d.resolve("auto_legacy.log")))
        assertFalse(Files.exists(d.resolve("manual_legacy.log")))
    }

    @Test fun cleanup_deletes_old_auto_and_stale_zero_byte_but_keeps_manual_and_fresh() {
        val d = dir()
        val oldAuto = d.resolve("auto").resolve("old.log"); oldAuto.writeText("x")
        val staleEmpty = d.resolve("auto").resolve("stale_empty.log"); Files.createFile(staleEmpty)  // 0 字节 + 2 天前
        val freshEmpty = d.resolve("auto").resolve("fresh_empty.log"); Files.createFile(freshEmpty)  // 0 字节但新（正在录的空流）
        val oldManual = d.resolve("manual").resolve("m.log"); oldManual.writeText("x")
        oldAuto.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        staleEmpty.toFile().setLastModified(now - 2L * 24 * 60 * 60 * 1000)
        oldManual.toFile().setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        m.cleanupOnStartup(d, capBytes = Long.MAX_VALUE, active = emptySet())
        assertFalse(Files.exists(oldAuto))      // 7 天 → 删
        assertFalse(Files.exists(staleEmpty))   // 0 字节超 1 天 → 删
        assertTrue(Files.exists(freshEmpty))    // 0 字节但新鲜 → 留
        assertTrue(Files.exists(oldManual))     // manual 永不自动删
    }

    @Test fun enforce_size_cap_deletes_oldest_first_skipping_active_and_manual() {
        val d = dir()
        fun file(name: String, ageMin: Long) {
            val f = d.resolve("auto").resolve(name)
            f.writeText("x")
            f.toFile().setLastModified(now - ageMin * 60_000L)
        }
        file("old.log", 100); file("mid.log", 50); file("new.log", 10); file("active.log", 5)
        d.resolve("manual").resolve("m.log").writeText("x")
        // 4 个 auto 各 1 字节，cap=2：删 old+mid（活动文件 active.log 保护）
        m.enforceSizeCap(d, capBytes = 2, active = setOf(d.resolve("auto").resolve("active.log")))
        assertFalse(Files.exists(d.resolve("auto").resolve("old.log")))
        assertFalse(Files.exists(d.resolve("auto").resolve("mid.log")))
        assertTrue(Files.exists(d.resolve("auto").resolve("new.log")))
        assertTrue(Files.exists(d.resolve("auto").resolve("active.log")))
        assertTrue(Files.exists(d.resolve("manual").resolve("m.log")))
    }

    @Test fun enforce_size_cap_noop_when_already_under_cap() {
        val d = dir()
        d.resolve("auto").resolve("only.log").writeText("x")
        m.enforceSizeCap(d, capBytes = 1024, active = emptySet())
        assertTrue(Files.exists(d.resolve("auto").resolve("only.log")))
    }

    @Test fun total_bytes_sums_log_files_in_both_subdirs_ignoring_others() {
        val d = dir()
        d.resolve("auto").resolve("a.log").writeText("x")
        d.resolve("manual").resolve("b.log").writeText("xy")
        d.resolve("auto").resolve("notes.txt").writeText("x")
        assertEquals(3L, m.totalBytes(d))
    }
}
