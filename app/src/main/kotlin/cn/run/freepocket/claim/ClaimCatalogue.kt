package cn.run.freepocket.claim

import cn.run.freepocket.data.PocketBackend
import cn.run.freepocket.domain.CatalogRules
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import cn.run.freepocket.model.OfficialProductUrl

/** One boundary for the existing cache format; queue decisions use the catalog model. */
internal class ClaimCatalogue(private val backend: PocketBackend) {
    fun items(): List<CatalogItem> = decode(backend.items)
    fun find(key: String): CatalogItem? = items().find { it.key == key }
    fun store(items: List<CatalogItem>) { backend.items = encode(items) }

    fun replace(category: Category, fresh: List<CatalogItem>) {
        for (item in fresh) {
            if (item.category != category || !OfficialProductUrl.accepts(category, item.url)) {
                throw JSONException("商品地址不属于官方商城")
            }
        }
        store(items().filter { it.category != category } + fresh.distinctBy { it.key }
            .map { it.copy(status = Ownership.UNKNOWN, fabOwnedTiers = emptySet()) })
        backend.saveCache()
    }

    fun applyOwnership(snapshot: JSONObject, fresh: Boolean, category: Category) {
        store(CatalogRules.applyOwnership(items(), snapshot, fresh, category))
    }

    fun invalidate(category: Category? = null, epic: Boolean = false) {
        store(items().map { item ->
            val matches = if (epic) item.category != Category.FAB else category == null || item.category == category
            if (matches) item.copy(status = Ownership.UNKNOWN, checkedAt = null, cached = false, statusReason = "",
                ownershipAccount = null, fabOwnedTiers = emptySet()) else item
        })
    }

    fun update(item: CatalogItem) { store(items().map { if (it.key == item.key) item else it }) }

    companion object {
        fun decode(array: JSONArray): List<CatalogItem> = List(array.length()) { CatalogItem.fromJson(array.getJSONObject(it)) }
        fun encode(items: List<CatalogItem>): JSONArray = JSONArray().apply { for (item in items) put(item.toJson()) }
    }
}
