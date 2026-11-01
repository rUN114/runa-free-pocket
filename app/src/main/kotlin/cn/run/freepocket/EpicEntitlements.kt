package cn.run.freepocket

import java.io.IOException

/** Completes the paginated read before publishing any negative ownership result. */
internal object EpicEntitlements {
    const val PAGE_SIZE = 1000
    const val MAX_PAGES = 100

    class Grant(@JvmField val namespace: String, @JvmField val id: String, @JvmField val active: Boolean)

    fun interface PageSource {
        @Throws(IOException::class)
        fun read(start: Int, count: Int): List<Grant>
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readAll(source: PageSource): List<Grant> {
        val owned = linkedMapOf<String, Grant>()
        val startedAt = System.nanoTime()
        for (page in 0 until MAX_PAGES) {
            if (System.nanoTime() - startedAt > 120_000_000_000L) {
                throw IOException("拥有凭证同步超时，未发布不完整结果")
            }
            val batch = source.read(page * PAGE_SIZE, PAGE_SIZE)
            if (batch.size > PAGE_SIZE) throw IOException("拥有凭证分页格式已变化，未发布不完整结果")
            for (grant in batch) {
                if (grant.active && grant.namespace.isNotEmpty() && grant.id.isNotEmpty()) {
                    owned[grant.namespace + ":" + grant.id] = grant
                }
            }
            if (batch.size < PAGE_SIZE) return owned.values.toList()
        }
        throw IOException("拥有凭证分页未读取完整，未将缺失项目判为未拥有")
    }
}
