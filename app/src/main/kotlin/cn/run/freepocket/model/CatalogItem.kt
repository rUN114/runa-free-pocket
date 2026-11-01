package cn.run.freepocket.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

data class CatalogIdentity(val namespace: String, val id: String)

data class CatalogItem(
    val key: String,
    val category: Category,
    val title: String,
    val url: String,
    val id: String = "",
    val namespace: String = "",
    val slug: String = "",
    val status: Ownership = Ownership.UNKNOWN,
    val availability: Availability = Availability.ACTIVE,
    val start: Long? = null,
    val end: Long? = null,
    val originalPrice: String = "",
    val cover: String = "",
    val screenshots: List<String> = emptyList(),
    val description: String = "",
    val publisher: String = "",
    val catalogItems: List<CatalogIdentity> = emptyList(),
    val checkedAt: Long? = null,
    val cached: Boolean = false,
    val statusReason: String = "",
    val ownershipAccount: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("category", category.wire)
        put("title", title)
        put("url", url)
        put("id", id)
        put("namespace", namespace)
        put("slug", slug)
        put("status", status.wire)
        put("availability", availability.wire)
        // The official-page adapter compares ISO dates; numeric mobile dates must normalize here.
        if (start != null) put("start", Instant.ofEpochMilli(start).toString())
        if (end != null) put("end", Instant.ofEpochMilli(end).toString())
        put("originalPrice", originalPrice)
        put("cover", cover)
        put("screenshots", JSONArray(screenshots))
        put("description", description)
        put("publisher", publisher)
        put("catalogItems", JSONArray().apply {
            for (entry in catalogItems) put(JSONObject().put("namespace", entry.namespace).put("id", entry.id))
        })
        if (checkedAt != null) put("checkedAt", checkedAt)
        put("cached", cached)
        put("statusReason", statusReason)
        if (ownershipAccount != null) put("ownershipAccount", ownershipAccount)
    }

    companion object {
        fun fromJson(value: JSONObject): CatalogItem {
            val identities = value.optJSONArray("catalogItems") ?: if (value.isNull("catalogItems")) JSONArray()
                else throw JSONException("商品库存身份格式已变化")
            val screenshots = value.optJSONArray("screenshots") ?: if (value.isNull("screenshots")) JSONArray()
                else throw JSONException("商品截图格式已变化")
            return CatalogItem(
                key = value.getString("key"),
                category = Category.fromWire(value.getString("category")),
                title = value.getString("title"),
                url = value.getString("url"),
                id = value.text("id"),
                namespace = value.text("namespace"),
                slug = value.text("slug"),
                status = Ownership.fromWire(value.text("status").ifEmpty { "unknown" }),
                availability = Availability.fromWire(value.text("availability").ifEmpty { "active" }),
                start = parseCatalogTime(value.opt("start"), "活动开始时间"),
                end = parseCatalogTime(value.opt("end"), "活动结束时间"),
                originalPrice = value.text("originalPrice"),
                cover = value.text("cover"),
                screenshots = List(screenshots.length()) { screenshots.getString(it) },
                description = value.text("description"),
                publisher = value.text("publisher"),
                catalogItems = List(identities.length()) {
                    val entry = identities.getJSONObject(it)
                    CatalogIdentity(entry.getString("namespace"), entry.getString("id"))
                },
                checkedAt = parseCatalogTime(value.opt("checkedAt"), "拥有状态同步时间"),
                cached = value.optBoolean("cached", false),
                statusReason = value.text("statusReason"),
                ownershipAccount = if (value.isNull("ownershipAccount")) null else value.getString("ownershipAccount")
            )
        }
    }
}

private fun JSONObject.text(name: String): String = if (isNull(name)) "" else get(name).toString()

/** Dates cross the cache and official-page JSON boundary as either epoch milliseconds or ISO text. */
internal fun parseCatalogTime(value: Any?, label: String): Long? {
    if (value == null || value === JSONObject.NULL || value == "") return null
    if (value is Number && value.toDouble().isFinite()) return value.toLong()
    if (value is String) {
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            throw JSONException("${label}无效")
        } catch (_: ArithmeticException) {
            throw JSONException("${label}超出范围")
        }
    }
    throw JSONException("${label}格式已变化")
}
