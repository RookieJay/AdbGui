package com.adbgui.core.update

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateVersionComparerTest {
    @Test fun remote_newer_minor() = assertTrue(UpdateVersionComparer.isNewer("1.1.0", "1.0.0"))
    @Test fun remote_newer_patch() = assertTrue(UpdateVersionComparer.isNewer("1.0.1", "1.0.0"))
    @Test fun remote_older() = assertFalse(UpdateVersionComparer.isNewer("1.0.0", "1.1.0"))
    @Test fun equal() = assertFalse(UpdateVersionComparer.isNewer("1.0.0", "1.0.0"))
    @Test fun remote_prerelease_vs_release() = assertFalse(UpdateVersionComparer.isNewer("1.0.0-beta", "1.0.0"))
    @Test fun rejects_bad_remote() {
        assertFailsWith<IllegalArgumentException> {
            UpdateVersionComparer.isNewer("1.0", "1.0.0")
        }
    }
}
