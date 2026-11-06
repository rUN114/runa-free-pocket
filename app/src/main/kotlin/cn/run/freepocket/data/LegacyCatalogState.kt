package cn.run.freepocket.data

import cn.run.freepocket.domain.CatalogRules
import cn.run.freepocket.model.Availability
import cn.run.freepocket.model.Ownership
import cn.run.freepocket.model.parseCatalogTime
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Compatibility boundary for 1.x journal targets; they need no presentation fields or URLs. */
internal object LegacyCatalogState {
    fun claimableNow(item: JSONObject, now: Long): Boolean = try {
        CatalogRules.claimableNow(Ownership.fromWire(item.optString("status", "unknown")),
            Availability.fromWire(item.optString("availability", "active")),
            parseCatalogTime(item.opt("start"), "开始时间"), parseCatalogTime(item.opt("end"), "结束时间"), now)
    } catch (_: JSONException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    fun invalidateOwnership(items: JSONArray, category: String) {
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val target = item.getString("category")
            if (category != "all" && category != target && !(category == "epic" && target != "fab")) continue
            item.put("status", "unknown")
            item.remove("checkedAt")
            item.remove("cached")
            item.remove("statusReason")
            item.remove("ownershipAccount")
        }
    }
}
