package app.echo

import org.json.JSONArray
import java.io.File

/**
 * 墓碑集合（已删除会话 id）的磁盘读写（R13-P2-2 / R13-P2-4）。
 *
 * 约束：
 * - 读损坏绝不静默返回空集：readOrNull() 返回 null，调用方必须拒绝删除并保留原文件，
 *   否则「空集 + 新 id」会覆盖旧墓碑，让已删会话在孤儿扫描里复活。
 * - merge / remove 是读-改-写，且以磁盘当前内容为底 merge，并发删除不会互相覆盖；
 *   write() 仅供空集初始化与测试，不得用于覆盖已有墓碑。
 */
class TombstoneStore(private val file: File) {
    /** @return 磁盘上的墓碑；文件不存在返回空集；内容损坏返回 null（原文件保留，不 rename）。 */
    fun readOrNull(): Set<String>? {
        if (!file.exists()) return emptySet()
        return runCatching {
            val arr = JSONArray(file.readText())
            val out = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
            }
            out
        }.getOrNull()
    }

    fun read(): Set<String> = readOrNull() ?: emptySet()

    /** 全量写回；返回是否成功。 */
    fun write(ids: Collection<String>): Boolean = runCatching {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        writeAtomic(arr.toString())
    }.isSuccess

    /**
     * 读磁盘 → 合并 extra → 写回。用于删除提交：别的删除刚写的墓碑必须保留。
     * @return 合并后的集合；磁盘损坏返回 null 且不写。
     */
    fun mergeAndWrite(extra: Collection<String>): Set<String>? {
        val disk = readOrNull() ?: return null
        val merged = LinkedHashSet(disk)
        merged.addAll(extra)
        return if (write(merged)) merged else null
    }

    /**
     * 读磁盘 → 合并 extra → 移除本次 id → 写回；用于删除失败回滚。
     * @return 回滚后的集合；磁盘损坏返回 null 且不写。
     */
    fun removeAndWrite(extra: Collection<String>, id: String): Set<String>? {
        val disk = readOrNull() ?: return null
        val merged = LinkedHashSet(disk)
        merged.addAll(extra)
        merged.remove(id)
        return if (write(merged)) merged else null
    }

    private fun writeAtomic(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + "." + System.nanoTime() + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (tmp.renameTo(file)) return
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }
}
