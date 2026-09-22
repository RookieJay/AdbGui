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

    // isAtLeast(installed, target): gate for skipping the MSI when the machine
    // already has the target version (or newer) installed.
    @Test fun at_least_equal() = assertTrue(UpdateVersionComparer.isAtLeast("1.2.1", "1.2.1"))
    @Test fun at_least_newer() = assertTrue(UpdateVersionComparer.isAtLeast("1.2.2", "1.2.1"))
    @Test fun at_least_older() = assertFalse(UpdateVersionComparer.isAtLeast("1.2.0", "1.2.1"))
    @Test fun at_least_release_over_prerelease() = assertTrue(UpdateVersionComparer.isAtLeast("1.2.1", "1.2.1-rc.1"))
    @Test fun at_least_prerelease_under_release() = assertFalse(UpdateVersionComparer.isAtLeast("1.2.1-rc.1", "1.2.1"))
    @Test fun rejects_bad_at_least() {
        assertFailsWith<IllegalArgumentException> {
            UpdateVersionComparer.isAtLeast("abc", "1.0.0")
        }
    }

    @Test fun rejects_bad_remote() {
        assertFailsWith<IllegalArgumentException> {
            UpdateVersionComparer.isNewer("1.0", "1.0.0")
        }
    }
}
