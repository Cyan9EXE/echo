package app.echo

/**
 * 纯 JVM 的落盘策略：索引写入节流、脏标记代数、恢复对账、坏索引重建合并。
 * 不依赖 Android / Compose，方便在 JVM 单测里直接覆盖（R8/R9 P1/P2）。
 */
internal object PersistencePolicy {
    /** 索引最多 30s 重写一次；流式期间 markDirty 很频繁，全量重写 3800 行会放大写 I/O。 */
    const val INDEX_WRITE_INTERVAL_MS = 30_000L

    /**
     * 本次 save 是否要写索引。
     * urgent（删除/新建/终态等 forceIndex）必须无条件写：否则 indexDirty 为 false 时
     * save 直接返回，删除的行或新建的会话会留在旧索引里（R8-P1-1）。
     */
    fun indexWriteDue(urgent: Boolean, indexDirty: Boolean, now: Long, lastIndexWriteAt: Long): Boolean =
        urgent || (indexDirty && now - lastIndexWriteAt >= INDEX_WRITE_INTERVAL_MS)

    /**
     * 启动索引加载完成前一律不写索引：此时内存可能只有新建会话，写出去会覆盖旧索引
     * 让大量会话冷启动后不可见（R9-P1-1）。加载完成后由 applyLoaded 补一次强制写。
     */
    fun indexDueAfterLoad(
        indexLoaded: Boolean,
        urgent: Boolean,
        indexDirty: Boolean,
        now: Long,
        lastIndexWriteAt: Long,
    ): Boolean = indexLoaded && indexWriteDue(urgent, indexDirty, now, lastIndexWriteAt)

    /** IO 写盘成功后能否清掉 indexDirty：写盘期间又发生脏变更（epoch 变了）时必须保留。 */
    fun mayClearIndexDirty(epochAtStart: Long, currentEpoch: Long): Boolean =
        epochAtStart == currentEpoch

    /**
     * 启动对账候选：消息文件里存在、但索引/内存/墓碑都没有的会话 id（R9-P1-2）。
     * candidates 由调用方按最近使用排序；本函数保持顺序、不截断，避免「每次只恢复 50 条」。
     */
    fun reconcileIds(
        indexIds: Set<String>,
        inMemoryIds: Set<String>,
        tombstoned: Set<String>,
        candidates: List<String>,
    ): List<String> = candidates.filterNot { it in indexIds || it in inMemoryIds || it in tombstoned }

    /**
     * 坏索引重建：索引里还能读出的行优先（保留 pinned/revived/imported/cost/title），
     * 再从消息文件重建的行补齐；同一 id 只保留第一次出现（valid 与 rebuilt 都用 putIfAbsent，
     * 避免重复 id 时 valid 行互相覆盖、与注释不符；R9-P3-7）。
     */
    fun <T> mergeRebuilt(validRows: List<T>, rebuiltRows: List<T>, id: (T) -> String): List<T> {
        val out = LinkedHashMap<String, T>()
        for (r in validRows) out.putIfAbsent(id(r), r)
        for (r in rebuiltRows) out.putIfAbsent(id(r), r)
        return out.values.toList()
    }

    /** 启动清理（删残留消息文件、撤墓碑）的前提：过滤墓碑后的索引已写盘，或本来就不需要重写（R10-P1-1 修法③）。 */
    fun startupCleanupAllowed(indexWritten: Boolean, needsIndexWrite: Boolean): Boolean =
        indexWritten || !needsIndexWrite

    /**
     * 墓碑并集：保留磁盘上已有的全部墓碑，再补本次新增的 id（R11-P2-3）。
     * 删除流程在单线程 IO 内「读-合并-写」，并发删除不会用旧快照覆盖新墓碑。
     */
    fun mergeTombstones(existing: Collection<String>, added: Collection<String>): Set<String> {
        val out = LinkedHashSet<String>(existing.size + added.size)
        out.addAll(existing)
        out.addAll(added)
        return out
    }

    /** 墓碑回滚（删除消息文件失败时用）：去掉本次 id，其余历史墓碑保留（R11-P2-5）。 */
    fun removeTombstone(existing: Collection<String>, id: String): Set<String> {
        val out = LinkedHashSet<String>(existing.size)
        for (item in existing) if (item != id) out.add(item)
        return out
    }

    /** 过滤删除中的会话：保存时不落盘、不进索引（R12-P1-1）。 */
    fun persistableIds(ids: Collection<String>, deleting: Set<String>): List<String> =
        ids.filterNot { it in deleting }
}
