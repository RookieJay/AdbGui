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
        val store = DeviceHistoryStore(dir, clock = { 1000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("192.168.1.50:5555", DeviceType.WIRELESS, "192.168.1.50", 5555)
        val loaded = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined).load()  // SAME dir
        assertEquals(1, loaded.size)
        assertEquals(1000L, loaded[0].lastConnectedAt)
        assertEquals("192.168.1.50", loaded[0].wirelessIp)
    }

    @Test
    fun setAlias_updates_alias_only() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("abc", DeviceType.USB, null, null)
        store.setAlias("abc", "My Phone")
        val e = store.load().first()
        assertEquals("My Phone", e.alias)
    }

    @Test
    fun remove_deletes_entry() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("abc", DeviceType.USB, null, null)
        store.remove("abc")
        assertEquals(0, store.load().size)
    }

    @Test
    fun upsert_without_alias_preserves_existing_alias() = runTest {
        val dir = Files.createTempDirectory("hist")
        val store = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("abc", DeviceType.USB, null, null, alias = "My Phone")
        store.upsert("abc", DeviceType.USB, null, null)  // no alias → must NOT wipe
        val e = store.load().first()
        assertEquals("My Phone", e.alias)
    }

    @Test
    fun touchLastUsed_updates_timestamp_and_preserves_other_fields() = runTest {
        val dir = Files.createTempDirectory("hist")
        val store = DeviceHistoryStore(dir, clock = { 1000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("abc", DeviceType.USB, null, null, alias = "Phone")
        // advance the clock and touch
        val store2 = DeviceHistoryStore(dir, clock = { 5000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store2.touchLastUsed("abc")
        val e = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined).load().first()
        assertEquals(5000L, e.lastUsedAt)
        assertEquals("Phone", e.alias)            // preserved
        assertEquals(DeviceType.USB, e.type)     // preserved
    }

    @Test
    fun touchLastUsed_creates_entry_for_unknown_serial() = runTest {
        // A USB-only device that was never wireless-connected has no history entry yet;
        // touching it (e.g. on selection) must create one so MRU sort can place it.
        val store = DeviceHistoryStore(dir(), clock = { 7000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.touchLastUsed("usb-only-serial")
        val e = store.load().first()
        assertEquals("usb-only-serial", e.serial)
        assertEquals(7000L, e.lastUsedAt)
    }

    @Test
    fun touchLastUsed_does_not_touch_lastConnectedAt() = runTest {
        val dir = Files.createTempDirectory("hist")
        val store = DeviceHistoryStore(dir, clock = { 1000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("abc", DeviceType.WIRELESS, "1.2.3.4", 5555)  // sets lastConnectedAt=1000
        DeviceHistoryStore(dir, clock = { 9000L }, io = kotlinx.coroutines.Dispatchers.Unconfined).touchLastUsed("abc")
        val e = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined).load().first()
        assertEquals(1000L, e.lastConnectedAt)  // unchanged
        assertEquals(9000L, e.lastUsedAt)
    }

    @Test
    fun setTag_updates_tag_only() = runTest {
        val dir = Files.createTempDirectory("hist")
        val store = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("abc", DeviceType.USB, null, null, alias = "Phone")
        store.setTag("abc", "lab")
        val e = store.load().first()
        assertEquals("lab", e.tag)
        assertEquals("Phone", e.alias)
    }

    @Test
    fun setTag_creates_entry_for_unknown_serial() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.setTag("new-serial", "tag1")
        val e = store.load().first()
        assertEquals("new-serial", e.serial)
        assertEquals("tag1", e.tag)
    }

    @Test
    fun setTag_null_clears_tag() = runTest {
        val dir = Files.createTempDirectory("hist")
        val store = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("abc", DeviceType.USB, null, null)
        store.setTag("abc", "lab")
        store.setTag("abc", null)
        val e = store.load().first()
        assertEquals(null, e.tag)
    }

    @Test
    fun clearTag_removes_tag_from_all_entries_in_one_atomic_write() = runTest {
        // Regression guard: clearing a tag must affect EVERY device bearing it, not just the
        // last writer. Per-device setTag(null) races on the read-modify-write in mutate() —
        // concurrent writers each start from the same base file and the last write wins, so
        // only one device's tag is actually cleared. clearTag does it in a single mutate.
        val dir = Files.createTempDirectory("cleartag")
        val store = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("a", DeviceType.USB, null, null)
        store.upsert("b", DeviceType.USB, null, null)
        store.upsert("c", DeviceType.USB, null, null)
        store.setTag("a", "lab")
        store.setTag("b", "lab")
        store.setTag("c", "other")
        store.clearTag("lab")
        val loaded = store.load().associateBy { it.serial }
        assertEquals(null, loaded["a"]?.tag)
        assertEquals(null, loaded["b"]?.tag)
        assertEquals("other", loaded["c"]?.tag)  // unrelated tag untouched
    }
}
