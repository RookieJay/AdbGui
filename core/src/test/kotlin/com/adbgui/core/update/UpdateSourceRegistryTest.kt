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
    @Test fun github_mirror_has_proxy_prefix() {
        val mirror = UpdateSourceRegistry.byId("github-mirror")
        assertNotNull(mirror)
        assertEquals("https://gh-proxy.com/", mirror.proxyPrefix)
    }
    @Test fun github_official_has_null_proxy_prefix() {
        val official = UpdateSourceRegistry.byId("github-official")
        assertNotNull(official)
        assertNull(official.proxyPrefix)
    }

    // resolve(id, customUrl): built-ins ignore customUrl; "custom" synthesizes a source from URL.
    @Test fun resolve_builtin_passes_through() {
        val src = UpdateSourceRegistry.resolve("github-mirror", "http://127.0.0.1/latest.json")
        assertNotNull(src)
        assertEquals("github-mirror", src.id)
    }

    @Test fun resolve_custom_with_http_url_returns_custom_source() {
        val src = UpdateSourceRegistry.resolve("custom", "http://127.0.0.1:8000/latest.json")
        assertNotNull(src)
        assertEquals("custom", src.id)
        assertEquals("http://127.0.0.1:8000/latest.json", src.manifestUrl)
        assertNull(src.proxyPrefix)
    }

    @Test fun resolve_custom_with_https_url_returns_custom_source() {
        val src = UpdateSourceRegistry.resolve("custom", "https://my-company.example/latest.json")
        assertNotNull(src)
        assertEquals("https://my-company.example/latest.json", src.manifestUrl)
    }

    @Test fun resolve_custom_with_blank_url_returns_null() {
        assertNull(UpdateSourceRegistry.resolve("custom", ""))
    }

    @Test fun resolve_custom_with_invalid_url_returns_null() {
        assertNull(UpdateSourceRegistry.resolve("custom", "ftp://example.com"))
        assertNull(UpdateSourceRegistry.resolve("custom", "file:///C:/x.json"))
        assertNull(UpdateSourceRegistry.resolve("custom", "not a url"))
    }

    @Test fun resolve_unknown_id_returns_null() {
        assertNull(UpdateSourceRegistry.resolve("nope", null))
    }
}
