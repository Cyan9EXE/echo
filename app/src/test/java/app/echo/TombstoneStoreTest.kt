package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 墓碑文件层测试（R13-P2-2）：真实临时目录 + 真实 IO，覆盖损坏保留、
 * 并发删除 merge、删除失败回滚、加载期清理 merge 四条不变量。
 */
class TombstoneStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): TombstoneStore = TombstoneStore(File(tmp.root, "store/tombstones.json"))

    private fun corruptFile(): File {
        val f = File(tmp.root, "store/tombstones.json")
        f.parentFile?.mkdirs()
        f.writeText("not-json{")
        return f
    }

    @Test
    fun missingFileReadsEmptyNotCorrupt() {
        val s = store()
        assertEquals(emptySet<String>(), s.readOrNull())
        assertEquals(emptySet<String>(), s.read())
    }

    @Test
    fun corruptedFileReturnsNullAndKeepsOriginalFile() {
        val s = store()
        val f = corruptFile()
        assertNull(s.readOrNull())
        // 原文件必须保留：不能 rename 成备份后让下一次读退化成空集（R13-P2-4）。
        assertTrue(f.exists())
        assertEquals("not-json{", f.readText())
        assertNull(s.readOrNull())
        // 损坏时拒绝写入，避免空集覆盖旧墓碑。
        assertNull(s.mergeAndWrite(listOf("a")))
        assertNull(s.removeAndWrite(listOf("a"), "a"))
        assertEquals("not-json{", f.readText())
    }

    @Test
    fun writeThenReadRoundTrip() {
        val s = store()
        assertTrue(s.write(listOf("a", "b")))
        assertEquals(setOf("a", "b"), s.read())
    }

    @Test
    fun mergeAndWriteKeepsConcurrentTombstones() {
        val s = store()
        assertTrue(s.write(listOf("a")))
        // 另一个删除刚写入 b；本次删除 a 时必须以磁盘为底 merge，不能把 b 丢掉。
        assertEquals(setOf("a", "b"), s.mergeAndWrite(listOf("b")))
        assertEquals(setOf("a", "b"), s.read())
    }

    @Test
    fun removeAndWriteKeepsOtherTombstones() {
        val s = store()
        assertTrue(s.write(listOf("a", "b")))
        assertEquals(setOf("b"), s.removeAndWrite(emptySet(), "a"))
        assertEquals(setOf("b"), s.read())
    }

    @Test
    fun loadCleanupMergeKeepsNewTombstoneWrittenAfterSnapshot() {
        val s = store()
        assertTrue(s.write(listOf("old")))
        // 加载快照只有 old；加载期间另一次删除写入 fresh；清理写回时两者都必须保留。
        assertTrue(s.write(listOf("old", "fresh")))
        assertEquals(setOf("old", "fresh"), s.mergeAndWrite(setOf("old")))
        assertEquals(setOf("old", "fresh"), s.read())
    }
}
