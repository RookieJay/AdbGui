package com.adbgui.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UpdateVersionTest {
    @Test fun parses_simple_triplet() {
        val v = UpdateVersion.parse("1.2.3")
        assertEquals(UpdateVersion(1, 2, 3), v)
        assertNull(v.prerelease)
    }
    @Test fun parses_with_prerelease() {
        val v = UpdateVersion.parse("1.0.0-beta.1")
        assertEquals(UpdateVersion(1, 0, 0, "beta.1"), v)
    }
    @Test fun rejects_garbage() {
        assertFailsWith<IllegalArgumentException> { UpdateVersion.parse("1.2") }
        assertFailsWith<IllegalArgumentException> { UpdateVersion.parse("x.y.z") }
    }
    @Test fun greater_than_major() {
        assertTrue(UpdateVersion.parse("2.0.0").isGreaterThan(UpdateVersion.parse("1.9.9")))
    }
    @Test fun greater_than_minor() {
        assertTrue(UpdateVersion.parse("1.10.0").isGreaterThan(UpdateVersion.parse("1.9.0")))
    }
    @Test fun equal_not_greater() {
        assertFalse(UpdateVersion.parse("1.0.0").isGreaterThan(UpdateVersion.parse("1.0.0")))
    }
    @Test fun prerelease_lower_than_release() {
        assertTrue(UpdateVersion.parse("1.0.0").isGreaterThan(UpdateVersion.parse("1.0.0-beta")))
        assertFalse(UpdateVersion.parse("1.0.0-beta").isGreaterThan(UpdateVersion.parse("1.0.0")))
    }
    @Test fun prerelease_order_by_identifier() {
        assertTrue(UpdateVersion.parse("1.0.0-rc.2").isGreaterThan(UpdateVersion.parse("1.0.0-rc.1")))
    }
    @Test fun rejects_leading_zeros() {
        assertFailsWith<IllegalArgumentException> { UpdateVersion.parse("01.0.0") }
        assertFailsWith<IllegalArgumentException> { UpdateVersion.parse("1.02.3") }
        assertFailsWith<IllegalArgumentException> { UpdateVersion.parse("1.0.00") }
    }
    @Test fun prerelease_lexical_beta_vs_rc() {
        assertTrue(UpdateVersion.parse("1.0.0-rc").isGreaterThan(UpdateVersion.parse("1.0.0-beta")))
    }
    @Test fun zero_versions_are_valid() {
        assertEquals(UpdateVersion(0, 0, 0), UpdateVersion.parse("0.0.0"))
    }
}
