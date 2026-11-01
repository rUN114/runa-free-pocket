package cn.run.freepocket.domain

import cn.run.freepocket.OfficialImages
import cn.run.freepocket.model.Availability
import cn.run.freepocket.model.CatalogIdentity
import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import cn.run.freepocket.model.Ownership
import cn.run.freepocket.model.parseCatalogTime
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object CatalogRules {
    fun parsePc(data: JSONObject, now: Long): List<CatalogItem> {
        val elements = data.optJSONObject("data")?.optJSONObject("Catalog")?.optJSONObject("searchStore")
            ?.optJSONArray("elements") ?: throw JSONException("Epic 活动返回格式已变化")
        val result = mutableListOf<CatalogItem>()
        for (index in 0 until elements.length()) {
            val item = elements.getJSONObject(index)
            val promotions = item.optJSONObject("promotions") ?: continue
            val offers = mutableListOf<Pair<Long, Long>>()
            for (name in listOf("promotionalOffers", "upcomingPromotionalOffers")) {
                val groups = promotions.optionalArray(name)
                for (groupIndex in 0 until groups.length()) {
                    val group = groups.getJSONObject(groupIndex).optionalArray("promotionalOffers")
                    for (offerIndex in 0 until group.length()) {
                        val offer = group.getJSONObject(offerIndex)
                        val discount = offer.optJSONObject("discountSetting")?.opt("discountPercentage")
                        if (discount !is Number || discount.toDouble() != 0.0) continue
                        val start = promotionDate(offer.opt("startDate")) ?: continue
                        val end = promotionDate(offer.opt("endDate")) ?: continue
                        if (now < end) offers.add(start to end)
                    }
                }
            }
            offers.sortBy { it.first }
            val promotion = offers.firstOrNull { it.first <= now } ?: offers.firstOrNull() ?: continue
            val upcoming = promotion.first > now
            val price = item.optJSONObject("price")?.optJSONObject("totalPrice")
            val discountPrice = price?.opt("discountPrice")
            if (!upcoming && (discountPrice !is Number || discountPrice.toDouble() != 0.0)) continue

            val mappings = item.optionalArray("offerMappings")
            var slug = ""
            for (mappingIndex in 0 until mappings.length()) {
                val mapping = mappings.getJSONObject(mappingIndex)
                if (mapping.text("pageType") == "productHome") {
                    slug = mapping.text("pageSlug")
                    break
                }
            }
            if (slug.isEmpty()) slug = item.optJSONObject("catalogNs")?.optionalArray("mappings")
                ?.optJSONObject(0)?.text("pageSlug").orEmpty()
            if (slug.isEmpty()) slug = item.text("productSlug")
            if (slug.isEmpty()) slug = item.text("urlSlug")
            val id = item.text("id")
            val namespace = item.text("namespace")
            val title = item.text("title")
            if (slug.isEmpty() || id.isEmpty() || namespace.isEmpty() || title.isEmpty()) continue
            if (!slug.matches(Regex("^[a-zA-Z0-9_-]+$"))) throw JSONException("商品地址无效")

            val identities = mutableListOf<CatalogIdentity>()
            val catalogItems = item.optionalArray("items")
            for (identityIndex in 0 until catalogItems.length()) {
                val identity = catalogItems.getJSONObject(identityIndex)
                val catalogId = identity.text("id")
                val catalogNamespace = identity.text("namespace")
                if (catalogId.isNotEmpty() && catalogNamespace.isNotEmpty()) {
                    identities.add(CatalogIdentity(catalogNamespace, catalogId))
                }
            }
            val images = item.optionalArray("keyImages")
            var cover = ""
            var wideCover = ""
            var foundWide = false
            val screenshots = linkedSetOf<String>()
            for (imageIndex in 0 until images.length()) {
                val image = images.getJSONObject(imageIndex)
                val type = image.text("type")
                val url = image.text("url").takeIf(OfficialImages::allowed).orEmpty()
                if (cover.isEmpty() && url.isNotEmpty()) cover = url
                if (!foundWide && type in listOf("OfferImageWide", "DieselStoreFrontWide", "DieselGameBox")) {
                    wideCover = url
                    foundWide = true
                }
                if (url.isNotEmpty() && type in listOf("featuredMedia", "GalleryImage", "Screenshot")) screenshots.add(url)
            }
            result.add(CatalogItem(
                key = "pc:$namespace:$id",
                category = Category.PC,
                title = title,
                url = "https://store.epicgames.com/en-US/p/$slug",
                id = id,
                namespace = namespace,
                start = promotion.first,
                end = promotion.second,
                availability = if (upcoming) Availability.UPCOMING else Availability.ACTIVE,
                catalogItems = identities,
                originalPrice = price?.optJSONObject("fmtPrice")?.text("originalPrice").orEmpty(),
                description = item.text("description").replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ").trim().take(3000),
                publisher = item.optJSONObject("seller")?.text("name").orEmpty(),
                cover = wideCover.ifEmpty { cover },
                screenshots = screenshots.toList()
            ))
        }
        return result
    }

    fun applyOwnership(items: List<CatalogItem>, snapshot: JSONObject, fresh: Boolean, category: Category): List<CatalogItem> {
        val accountId = snapshot.text("accountId")
        val checkedAt = snapshot.opt("checkedAt")
        if (accountId.isEmpty() || checkedAt !is Number || !checkedAt.toDouble().isFinite()) {
            throw JSONException("账号库存身份或同步时间缺失")
        }
        val ownedArray = snapshot.optJSONArray("owned") ?: throw JSONException("账号库存返回格式已变化")
        val owned = mutableSetOf<CatalogIdentity>()
        for (index in 0 until ownedArray.length()) {
            val entry = ownedArray.getJSONObject(index)
            owned.add(CatalogIdentity(entry.getString("namespace"), entry.getString("id")))
        }
        val checkArray = snapshot.optionalArray("checks")
        val checks = mutableMapOf<CatalogIdentity, Boolean>()
        for (index in 0 until checkArray.length()) {
            val entry = checkArray.getJSONObject(index)
            val identity = CatalogIdentity(entry.getString("namespace"), entry.getString("id"))
            // Only protocol booleans establish an ownership result; text cannot become negative proof.
            val hasItem = entry.opt("owned")
            if (hasItem is Boolean) checks[identity] = hasItem
        }
        for ((identity, hasItem) in checks) if (hasItem) owned.add(identity)
        return items.map { item ->
            if (item.category == Category.FAB || item.category != category) return@map item
            val ids = item.catalogItems
            if (ids.isEmpty() || ids.any { it.namespace.isEmpty() || it.id.isEmpty() }) {
                return@map item.copy(status = Ownership.UNKNOWN,
                    statusReason = "该商品暂时无法匹配账号库存，请查看官方商品页面",
                    ownershipAccount = accountId, checkedAt = checkedAt.toLong(), cached = !fresh)
            }
            val matches = ids.count { it in owned }
            val hasAll = matches == ids.size
            val fullyChecked = ids.all { it in owned || checks[it] == false }
            val status: Ownership
            val reason: String
            if (hasAll) {
                status = Ownership.OWNED
                reason = "账号拥有凭证已确认"
            } else if (fresh && fullyChecked) {
                status = if (matches > 0) Ownership.PARTIAL else Ownership.UNOWNED
                reason = if (matches > 0) "组合商品中仅部分已拥有" else "官方商品拥有权限查询确认未拥有"
            } else {
                status = Ownership.UNKNOWN
                reason = if (fresh) "直接凭证未匹配，仍需查询该版本的商品拥有权限" else "等待重新同步"
            }
            item.copy(status = status, ownershipAccount = accountId, checkedAt = checkedAt.toLong(),
                cached = !fresh, statusReason = reason)
        }
    }

    fun claimableNow(item: CatalogItem, now: Long): Boolean =
        claimableNow(item.status, item.availability, item.start, item.end, now)

    fun claimableNow(status: Ownership, availability: Availability, start: Long?, end: Long?, now: Long): Boolean {
        if (status == Ownership.OWNED || status == Ownership.EXPIRED || availability == Availability.EXPIRED) return false
        if (end != null && end <= now) return false
        if (start != null) return start <= now
        return availability == Availability.ACTIVE
    }

    fun availabilityAt(item: CatalogItem, now: Long): Availability {
        if (item.end != null && item.end <= now) return Availability.EXPIRED
        if (item.start != null) return if (item.start > now) Availability.UPCOMING else Availability.ACTIVE
        return item.availability
    }

    private fun promotionDate(value: Any?): Long? = try {
        parseCatalogTime(value, "优惠时间")
    } catch (_: JSONException) {
        // A malformed offer has no verifiable free window and is excluded, like the website adapter.
        null
    }

    private fun JSONObject.optionalArray(name: String): JSONArray = optJSONArray(name)
        ?: if (isNull(name)) JSONArray() else throw JSONException("${name}返回格式已变化")

    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else get(name).toString()
}
