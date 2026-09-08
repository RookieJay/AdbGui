package com.adbgui.core.update

import com.adbgui.core.log.InMemoryLogger
import com.adbgui.core.log.LogLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private val src = UpdateSource("github-official", "GitHub 官方", "https://example.com/latest.json")
    private val log = InMemoryLogger(LogLevel.DEBUG) { 0L }

    private fun manifest(version: String): String = """
        {
          "version": "$version",
          "url": "https://example.com/AdbGui-$version.msi",
          "sha256": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        }
    """.trimIndent()

    private class FakeFetcher(private val textFor: (String) -> String?) : UpdateManifestFetcher {
        override suspend fun fetch(url: String): String =
            textFor(url) ?: error("no stub for $url")
    }

    @Test fun available_when_remote_newer() = runTest {
        val checker = UpdateChecker(FakeFetcher { manifest("1.1.0") }, "1.0.0", log)
        val r = checker.check(src)
        assertIs<UpdateCheckResult.UpdateAvailable>(r)
        assertEquals("1.1.0", r.manifest.version)
    }

    @Test fun no_update_when_equal() = runTest {
        val checker = UpdateChecker(FakeFetcher { manifest("1.0.0") }, "1.0.0", log)
        assertIs<UpdateCheckResult.NoUpdate>(checker.check(src))
    }

    @Test fun no_update_when_remote_older() = runTest {
        val checker = UpdateChecker(FakeFetcher { manifest("0.9.0") }, "1.0.0", log)
        assertIs<UpdateCheckResult.NoUpdate>(checker.check(src))
    }

    @Test fun error_when_fetch_throws() = runTest {
        val checker = UpdateChecker(FakeFetcher { null }, "1.0.0", log)
        val r = checker.check(src)
        assertIs<UpdateCheckResult.Error>(r)
        assertTrue(r.message.contains("fetch"))
    }

    @Test fun error_when_manifest_invalid() = runTest {
        val checker = UpdateChecker(FakeFetcher { "{not json" }, "1.0.0", log)
        val r = checker.check(src)
        assertIs<UpdateCheckResult.Error>(r)
        assertEquals("{not json", r.raw)
    }

    @Test fun logs_check_at_info() = runTest {
        val checker = UpdateChecker(FakeFetcher { manifest("1.1.0") }, "1.0.0", log)
        checker.check(src)
        assertTrue(log.entries.any { it.message.contains("update") && it.level == LogLevel.INFO })
    }
}
