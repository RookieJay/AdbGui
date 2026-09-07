package com.adbgui.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateSourceRegistryTest {
    @Test fun default_is_github_official() {
        assertEquals("github-official", UpdateSourceRegistry.default.id)
    }
    @Test fun byId_finds_existing() {
        assertNotNull(UpdateSourceRegistry.byId("github-official"))
    }
    @Test fun byId_returns_null_for_unknown() {
        assertNull(UpdateSourceRegistry.byId("nope"))
    }
    @Test fun ids_are_unique() {
        val ids = UpdateSourceRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
    @Test fun all_urls_are_https() {
        UpdateSourceRegistry.all.forEach { assertTrue(it.manifestUrl.startsWith("https://"), "${it.id} not https") }
    }
}
