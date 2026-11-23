package app.echo

/**
 * 纯 JVM 的落盘策略：索引写入节流、脏标记代数、孤儿会话文件、坏索引重建合并。
 * 不依赖 Android / Compose，方便在 JVM 单测里直接覆盖（R8-P1-1 / P1-2 / P2-2 / P2-5）。
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

    /** IO 写盘成功后能否清掉 indexDirty：写盘期间又发生脏变更（epoch 变了）时必须保留。 */
    fun mayClearIndexDirty(epochAtStart: Long, currentEpoch: Long): Boolean =
        epochAtStart == currentEpoch

    /**
     * 消息文件里存在、但索引和内存都没有的会话 id：导入写了文件但索引没落、
     * 或索引丢行时会变成幽灵会话；启动时按此对账补齐（R8-P1-2 / P2-5）。
     */
    fun orphanSessionIds(
        indexIds: Set<String>,
        inMemoryIds: Set<String>,
        fileIds: Set<String>,
        limit: Int = 50,
    ): List<String> = fileIds
        .filterNot { it in indexIds || it in inMemoryIds }
        .sorted()
        .take(limit.coerceAtLeast(0))

    /**
     * 坏索引重建：索引里还能读出的行优先（保留 pinned/revived/imported/cost/title），
     * 再从消息文件重建的行补齐；同一 id 只保留第一次出现，避免列表里出现两份（R8-P2-2）。
     */
    fun <T> mergeRebuilt(validRows: List<T>, rebuiltRows: List<T>, id: (T) -> String): List<T> {
        val out = LinkedHashMap<String, T>()
        for (r in validRows) out[id(r)] = r
        for (r in rebuiltRows) out.putIfAbsent(id(r), r)
        return out.values.toList()
    }
}
