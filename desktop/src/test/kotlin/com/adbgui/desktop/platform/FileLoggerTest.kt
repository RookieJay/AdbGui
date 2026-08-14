package com.adbgui.desktop.platform

import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class FileLoggerTest {
    @Test
    fun writes_info_and_above_to_file() = runTest {
        val dir = Files.createTempDirectory("filelog")
        val logger = FileLogger(dir, LogLevel.INFO, clock = { 0L })
        logger.debug("skip")
        logger.info("hello")
        logger.error("boom", RuntimeException("x"))
        logger.flush()
        val content = Files.readString(dir.resolve("adbgui.log"))
        assertTrue(content.contains("hello"))
        assertTrue(!content.contains("skip"))
        assertTrue(content.contains("boom"))
    }
}
