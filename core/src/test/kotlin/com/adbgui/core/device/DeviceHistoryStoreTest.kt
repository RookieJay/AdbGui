package com.adbgui.core.device

import com.adbgui.core.domain.DeviceType
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceHistoryStoreTest {
    private fun dir() = Files.createTempDirectory("hist")

    @Test
    fun upsert_sets_lastConnectedAt_and_persists() = runTest {
        val dir = dir()  // ONE temp dir for both stores
        val store = DeviceHistoryStore(dir, clock = { 1000L })
        store.upsert("192.168.1.50:5555", DeviceType.WIRELESS, "192.168.1.50", 5555)
        val loaded = DeviceHistoryStore(dir, clock = { 0L }).load()  // SAME dir
        assertEquals(1, loaded.size)
        assertEquals(1000L, loaded[0].lastConnectedAt)
        assertEquals("192.168.1.50", loaded[0].wirelessIp)
    }

    @Test
    fun setAlias_updates_alias_only() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 0L })
        store.upsert("abc", DeviceType.USB, null, null)
        store.setAlias("abc", "My Phone")
        val e = store.load().first()
        assertEquals("My Phone", e.alias)
    }

    @Test
    fun remove_deletes_entry() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 0L })
        store.upsert("abc", DeviceType.USB, null, null)
        store.remove("abc")
        assertEquals(0, store.load().size)
    }

    @Test
    fun upsert_without_alias_preserves_existing_alias() = runTest {
        val dir = Files.createTempDirectory("hist")
        val store = DeviceHistoryStore(dir, clock = { 0L })
        store.upsert("abc", DeviceType.USB, null, null, alias = "My Phone")
        store.upsert("abc", DeviceType.USB, null, null)  // no alias → must NOT wipe
        val e = store.load().first()
        assertEquals("My Phone", e.alias)
    }
}
