package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 8/9 轮 P1-1 / P1-2 / P2-1 / P2-2 的纯 JVM 回归测试。 */
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
    fun indexNeverWrittenBeforeLoadFinished() {
        // 启动索引尚未加载完成时，即使 urgent 也不能写索引，否则会用不完整列表覆盖旧索引（R9-P1-1）。
        assertFalse(
            PersistencePolicy.indexDueAfterLoad(
                indexLoaded = false, urgent = true, indexDirty = true,
                now = 999_999L, lastIndexWriteAt = 0L,
            ),
        )
        assertTrue(
            PersistencePolicy.indexDueAfterLoad(
                indexLoaded = true, urgent = true, indexDirty = false,
                now = 999_999L, lastIndexWriteAt = 999_999L,
            ),
        )
    }

    @Test
    fun indexDirtySurvivesConcurrentWrite() {
        // 写盘开始时代际为 3；期间 markDirty 让代际变 4，IO 回调不能清脏。
        assertTrue(PersistencePolicy.mayClearIndexDirty(3L, 3L))
        assertFalse(PersistencePolicy.mayClearIndexDirty(3L, 4L))
    }

    @Test
    fun reconcileKeepsOrderAndExcludesKnownIds() {
        // 候选按最近使用排序；索引/内存/墓碑里的 id 都要排除，且不截断（R9-P1-2）。
        val candidates = (0 until 80).map { "s" + it }
        val out = PersistencePolicy.reconcileIds(
            indexIds = setOf("s0", "s1"),
            inMemoryIds = setOf("s2"),
            tombstoned = setOf("s3"),
            candidates = candidates,
        )
        assertEquals(76, out.size)
        assertEquals("s4", out.first())
        assertEquals("s79", out.last())
    }

    @Test
    fun reconcileWithEmptyCandidates() {
        assertEquals(
            emptyList<String>(),
            PersistencePolicy.reconcileIds(emptySet(), emptySet(), emptySet(), emptyList()),
        )
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

    @Test
    fun mergeRebuiltDuplicateValidIdsKeepFirstOccurrence() {
        // 重复 valid id：第一行优先（R9-P3-7，与注释一致）。
        class Row(val id: String, val pinned: Boolean)
        val valid = listOf(Row("a", true), Row("a", false))
        val merged = PersistencePolicy.mergeRebuilt(valid, emptyList<Row>()) { it.id }
        assertEquals(1, merged.size)
        assertTrue(merged.single().pinned)
    }
    @Test
    fun cleanupBlockedUntilFilteredIndexWritten() {
        assertEquals(false, PersistencePolicy.startupCleanupAllowed(false, true))
        assertEquals(true, PersistencePolicy.startupCleanupAllowed(true, true))
        assertEquals(true, PersistencePolicy.startupCleanupAllowed(false, false))
    }

}
