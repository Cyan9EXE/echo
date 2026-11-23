package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 8 轮 P1-1 / P1-2 / P2-2 / P2-5 的纯 JVM 回归测试。 */
class SessionPersistenceTest {

    @Test
    fun urgentAlwaysWritesIndex() {
        // forceIndex 必须绕过 indexDirty 前置：删除会话后 dirtyIds 为空，索引仍要落。
        assertTrue(PersistencePolicy.indexWriteDue(true, false, now = 1_000L, lastIndexWriteAt = 999_000L))
        assertTrue(PersistencePolicy.indexWriteDue(true, true, now = 1_000L, lastIndexWriteAt = 999_000L))
    }

    @Test
    fun dirtyIndexWaitsForInterval() {
        val last = 10_000_000L
        assertFalse(PersistencePolicy.indexWriteDue(false, false, last + 60_000L, last))
        assertFalse(PersistencePolicy.indexWriteDue(false, true, last + 29_999L, last))
        assertTrue(PersistencePolicy.indexWriteDue(false, true, last + 30_000L, last))
    }

    @Test
    fun indexDirtySurvivesConcurrentWrite() {
        // 写盘开始时代际为 3；期间 markDirty 让代际变 4，IO 回调不能清脏。
        assertTrue(PersistencePolicy.mayClearIndexDirty(3L, 3L))
        assertFalse(PersistencePolicy.mayClearIndexDirty(3L, 4L))
    }

    @Test
    fun orphanIdsExcludeIndexedAndInMemory() {
        val orphans = PersistencePolicy.orphanSessionIds(
            indexIds = setOf("a", "b"),
            inMemoryIds = setOf("c"),
            fileIds = setOf("a", "b", "c", "d", "e"),
        )
        assertEquals(listOf("d", "e"), orphans)
    }

    @Test
    fun orphanIdsAreLimited() {
        val files = (0 until 80).map { "s" + it }.toSet()
        val orphans = PersistencePolicy.orphanSessionIds(emptySet(), emptySet(), files)
        assertEquals(50, orphans.size)
        assertEquals(emptyList<String>(), PersistencePolicy.orphanSessionIds(emptySet(), emptySet(), files, limit = 0))
    }

    @Test
    fun mergeRebuiltKeepsValidRowMetadataAndDedupes() {
        class Row(val id: String, val pinned: Boolean)
        val valid = listOf(Row("a", true), Row("b", false))
        val rebuilt = listOf(Row("a", false), Row("b", true), Row("c", false))
        val merged = PersistencePolicy.mergeRebuilt(valid, rebuilt) { it.id }
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        // 坏索引里还能读出的 pinned 必须保留，不能被重建行覆盖。
        assertTrue(merged.first { it.id == "a" }.pinned)
        assertFalse(merged.first { it.id == "b" }.pinned)
    }
}
