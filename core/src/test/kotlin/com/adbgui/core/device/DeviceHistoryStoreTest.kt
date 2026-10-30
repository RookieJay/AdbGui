package com.adbgui.core.device

import com.adbgui.core.domain.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    @Test
    fun renameTag_replaces_tag_on_all_bearing_entries_and_preserves_rest() = runTest {
        val dir = Files.createTempDirectory("renametag")
        val store = DeviceHistoryStore(dir, clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("a", DeviceType.USB, null, null, alias = "Phone A")
        store.upsert("b", DeviceType.USB, null, null)
        store.upsert("c", DeviceType.USB, null, null)
        store.setTag("a", "lab")
        store.setTag("b", "lab")
        store.setTag("c", "other")
        store.renameTag("lab", "desk")
        val loaded = store.load().associateBy { it.serial }
        assertEquals("desk", loaded["a"]?.tag)
        assertEquals("desk", loaded["b"]?.tag)
        assertEquals("other", loaded["c"]?.tag)          // unrelated tag untouched
        assertEquals("Phone A", loaded["a"]?.alias)      // other fields preserved
    }

    @Test
    fun renameTag_to_existing_tag_merges_groups() = runTest {
        // Renaming "lab" onto existing "desk" is a merge: every "lab" device joins "desk",
        // and the "lab" group disappears.
        val store = DeviceHistoryStore(Files.createTempDirectory("renametag"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("a", DeviceType.USB, null, null)
        store.upsert("b", DeviceType.USB, null, null)
        store.upsert("c", DeviceType.USB, null, null)
        store.setTag("a", "lab")
        store.setTag("b", "desk")
        store.setTag("c", "desk")
        store.renameTag("lab", "desk")
        val loaded = store.load().associateBy { it.serial }
        assertEquals("desk", loaded["a"]?.tag)
        assertEquals("desk", loaded["b"]?.tag)
        assertEquals("desk", loaded["c"]?.tag)
        assertEquals(0, loaded.values.count { it.tag == "lab" })  // old tag is gone
    }

    @Test
    fun renameTag_blank_newTag_is_noop() = runTest {
        // Clearing is clearTag's job; a blank rename must not wipe tags behind the UI's back.
        val store = DeviceHistoryStore(Files.createTempDirectory("renametag"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("a", DeviceType.USB, null, null)
        store.setTag("a", "lab")
        store.renameTag("lab", "")
        store.renameTag("lab", "   ")
        assertEquals("lab", store.load().first().tag)
    }

    @Test
    fun mergeOnConnect_merges_entries_with_same_deviceId_and_keeps_alias() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 1000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        // 旧端口条目：曾经的连接已学到 deviceId（规则 1 的前置状态）
        store.mergeOnConnect("af199bb4", "10.0.5.221:38897", DeviceType.WIRELESS, "10.0.5.221", 38897)
        store.setAlias("10.0.5.221:38897", "我的手机")
        // 端口漂移后 adb server 自动连上产生的新条目（无 deviceId）
        store.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

        val removed = store.mergeOnConnect("af199bb4", "10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

        val entries = store.load()
        assertEquals(1, entries.size)
        assertEquals("10.0.5.221:37751", entries[0].serial)
        assertEquals("af199bb4", entries[0].deviceId)
        assertEquals("我的手机", entries[0].alias)
        assertEquals(37751, entries[0].wirelessPort)
        assertEquals(listOf("10.0.5.221:38897"), removed.map { it.serial })
    }

    @Test
    fun mergeOnConnect_merges_usb_entry_with_same_serial_as_deviceId() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 1000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("af199bb4", DeviceType.USB, null, null)
        store.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

        store.mergeOnConnect("af199bb4", "10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

        val entries = store.load()
        assertEquals(1, entries.size)
        assertEquals("10.0.5.221:37751", entries[0].serial)
        assertEquals(DeviceType.WIRELESS, entries[0].type)
        assertEquals("af199bb4", entries[0].deviceId)
    }

    @Test
    fun mergeOnConnect_keeps_stale_same_ip_entry_with_different_alias() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 1000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.upsert("10.0.5.221:38897", DeviceType.WIRELESS, "10.0.5.221", 38897)
        store.setAlias("10.0.5.221:38897", "另一个设备的别名")
        store.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

        store.mergeOnConnect("af199bb4", "10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

        assertEquals(2, store.load().size)
    }

    @Test
    fun mergeOnConnect_creates_entry_when_group_empty() = runTest {
        val store = DeviceHistoryStore(dir(), clock = { 1000L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        store.mergeOnConnect("af199bb4", "10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)
        val e = store.load().single()
        assertEquals("af199bb4", e.deviceId)
        assertEquals(1000L, e.lastConnectedAt)
    }

    @Test
    fun concurrent_mutations_do_not_lose_writes() = kotlinx.coroutines.runBlocking {
        // Two parallel upserts on different serials must both persist. Before the Mutex
        // in mutate(), concurrent read-modify-writes each started from the same on-disk
        // baseline; the last writer won, silently dropping the other entry's alias.
        val dir = Files.createTempDirectory("concurrent")
        val store = DeviceHistoryStore(dir, clock = { 1L })   // default Dispatchers.IO
        // Pre-seed both serials with an alias each
        store.upsert("serial-a", DeviceType.USB, null, null, alias = "A-alias")
        store.upsert("serial-b", DeviceType.USB, null, null, alias = "B-alias")

        // Concurrent renames from a parallel dispatcher — the mutex serializes the two
        // read-modify-writes so both survive. Without it, each writer reads the same
        // baseline and the last write clobbers the first's change.
        val scope = CoroutineScope(Dispatchers.Default)
        val job1 = scope.async { store.setAlias("serial-a", "A-new") }
        val job2 = scope.async { store.setAlias("serial-b", "B-new") }
        listOf(job1, job2).awaitAll()

        val loaded = store.load().associateBy { it.serial }
        assertEquals("A-new", loaded["serial-a"]?.alias, "serial-a alias must survive concurrent mutation")
        assertEquals("B-new", loaded["serial-b"]?.alias, "serial-b alias must survive concurrent mutation")
    }
}
