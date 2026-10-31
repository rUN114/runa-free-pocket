package cn.run.freepocket

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList
import java.util.UUID

/** Local batch progress. Reservations survive history trimming and are never replayed as orders. */
internal class ClaimJournal @Throws(JSONException::class) constructor(json: String) {
    private val root: JSONObject = JSONObject(json)
    private val runs: JSONArray
    private val reservations: JSONObject

    init {
        if (root.length() == 0) {
            root.put("schema", 1).put("runs", JSONArray()).put("reservations", JSONObject())
        }
        if (root.getInt("schema") != 1) throw JSONException("领取记录版本无法识别")
        runs = root.getJSONArray("runs")
        reservations = root.getJSONObject("reservations")
        for (i in 0 until runs.length()) validateRun(runs.getJSONObject(i))
        val current = current()
        if (current != null && "running" == current.getString("state")) pause("应用已中断，继续前将重新核实库存")
    }

    fun current(): JSONObject? = runs.optJSONObject(0)

    fun serialize(): String = root.toString()

    fun running(): Boolean = current()?.optString("state") == "running"

    @Throws(JSONException::class)
    fun start(country: String, categories: List<String>, selected: JSONArray?) {
        val run = JSONObject().put("id", UUID.randomUUID().toString()).put("state", "running")
            .put("startedAt", System.currentTimeMillis()).put("country", country)
            .put("categories", JSONArray(categories)).put("all", selected == null)
            .put("targets", selected ?: JSONArray())
            .put("owners", JSONObject()).put("planned", JSONObject())
            .put("errors", JSONObject()).put("entries", JSONArray())
        val retained = JSONArray().put(run)
        for (i in 0 until minOf(runs.length(), 19)) retained.put(runs.getJSONObject(i))
        while (runs.length() > 0) runs.remove(runs.length() - 1)
        for (i in 0 until retained.length()) runs.put(retained.getJSONObject(i))
    }

    @Throws(JSONException::class)
    fun categories(): List<String> {
        val result = ArrayList<String>()
        val categories = current()!!.getJSONArray("categories")
        for (i in 0 until categories.length()) result.add(categories.getString(i))
        return result
    }

    @Throws(JSONException::class)
    fun resume(country: String) {
        val current = current() ?: throw JSONException("没有可继续的领取记录")
        if (country != current.getString("country")) throw JSONException("账号地区已变化，请开始新的领取任务")
        current.put("state", "running")
    }

    @Throws(JSONException::class)
    fun bind(category: String, accountId: String?, displayName: String?) {
        if (accountId == null || accountId.isEmpty()) throw JSONException("无法确认领取账号身份")
        val owners = current()!!.getJSONObject("owners")
        val platformKey = platform(category)
        val previous = owners.optJSONObject(platformKey)
        if (previous != null && accountId != previous.getString("id"))
            throw JSONException("当前账号与领取记录不一致，请切回原账号或开始新任务")
        owners.put(platformKey, JSONObject().put("id", accountId).put("name", displayName))
    }

    @Throws(JSONException::class)
    private fun reservationKey(entry: JSONObject): String {
        val owner = current()!!.getJSONObject("owners").getJSONObject(platform(entry.getString("category")))
        return platform(entry.getString("category")) + ":" + owner.getString("id") + ":" + entry.getString("key")
    }

    @Throws(JSONException::class)
    fun entry(key: String): JSONObject? {
        val current = current() ?: return null
        val entries = current.getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (key == entry.getString("key")) return entry
        }
        return null
    }

    @Throws(JSONException::class)
    fun plan(category: String, catalogue: JSONArray, now: Long) {
        val current = current()!!
        val planned = current.getJSONObject("planned")
        if (!planned.optBoolean(category)) {
            val entries = current.getJSONArray("entries")
            val candidates = if (current.getBoolean("all")) catalogue else current.getJSONArray("targets")
            for (i in 0 until candidates.length()) {
                val item = candidates.getJSONObject(i)
                if (category != item.getString("category") || entry(item.getString("key")) != null) continue
                entries.put(JSONObject().put("key", item.getString("key")).put("category", category)
                    .put("title", item.getString("title")).put("status", "pending").put("reason", "等待处理"))
            }
            planned.put(category, true)
        }
        current.getJSONObject("errors").remove(category)
        val entries = current.getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (category != entry.getString("category")) continue
            val status = entry.getString("status")
            if (status in listOf("claimed", "owned", "skipped")) continue
            val item = find(catalogue, entry.getString("key"))
            if (item != null && item.optString("status") == "owned" && !item.optBoolean("cached")) {
                result(entry.getString("key"), "owned", "官方库存已确认")
            } else if (reservations.has(reservationKey(entry))) {
                update(entry, "uncertain", "此前可能已提交，仅重新核实，不自动重复下单")
            } else if (item == null || !MainActivity.claimableNow(item, now)) {
                update(entry, "skipped", if (item == null) "已不在当前官方活动中" else "活动未开始、已结束或已拥有")
            } else if (item.optString("status") !in listOf("unowned", "partial") || item.optBoolean("cached")) {
                entry.put("pageCheck", true)
                update(entry, "pending", "先到官方商品页只读核实，尚未允许领取")
            } else {
                entry.put("pageCheck", false)
                update(entry, "pending", "等待领取")
            }
        }
    }

    @Throws(JSONException::class)
    fun work(category: String): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        val entries = current()!!.getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (category == entry.getString("category") && entry.getString("status") in listOf("pending", "uncertain")) result.add(entry)
        }
        return result
    }

    @Throws(JSONException::class)
    fun working(key: String) {
        val entry = entry(key)!!
        if (entry.getString("status") != "uncertain") update(entry, "working", "正在核实并处理")
    }

    @Throws(JSONException::class)
    fun reserve(key: String, stage: String) {
        if (stage !in listOf("product", "submit")) throw JSONException("领取步骤无法识别")
        val entry = entry(key)!!
        if (entry.optBoolean("pageCheck")) throw JSONException("必须先完成官方商品页核实")
        if (entry.optBoolean(stage + "Authorized")) throw JSONException("此领取步骤已处理，请先核实订单")
        val current = current()!!
        val reservation = reservationKey(entry)
        val previous = reservations.optString(reservation)
        if (previous.isNotEmpty() && previous != current.getString("id"))
            throw JSONException("此项目有待核实订单，禁止再次自动提交")
        reservations.put(reservation, current.getString("id"))
        entry.put(stage + "Authorized", true)
        update(entry, "uncertain", "领取操作已准备，恢复时须先核实库存")
    }

    @Throws(JSONException::class)
    fun prechecked(key: String) {
        val entry = entry(key)!!
        if (!entry.optBoolean("pageCheck") || reservations.has(reservationKey(entry)))
            throw JSONException("该项目不能从只读核实转为自动领取")
        entry.put("pageCheck", false)
        update(entry, "pending", "官方商品页已确认未拥有，等待领取")
    }

    @Throws(JSONException::class)
    fun result(key: String, status: String, reason: String) {
        val entry = entry(key)!!
        val current = current()!!
        val reservation = reservationKey(entry)
        if (status == "owned") {
            val claimed = current.getString("id") == reservations.optString(reservation)
            reservations.remove(reservation)
            update(entry, if (claimed) "claimed" else "owned", reason)
        } else if (reservations.has(reservation)) {
            update(entry, "uncertain", "订单结果待核实；请同步或在官网查看，未自动重下单")
        } else {
            update(entry, if (status == "expired") "skipped" else "failed", reason)
        }
    }

    @Throws(JSONException::class)
    fun categoryFailed(category: String, reason: String) {
        current()!!.getJSONObject("errors").put(category, reason)
    }

    @Throws(JSONException::class)
    fun pause(reason: String) {
        val current = current() ?: return
        current.put("state", "paused").put("note", reason)
        val entries = current.getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("status") == "working") update(entry, "pending", "处理已中断，继续前重新查库")
        }
    }

    @Throws(JSONException::class)
    fun finish() {
        current()!!.put("state", "done").put("finishedAt", System.currentTimeMillis())
    }

    @Throws(JSONException::class)
    fun publicRuns(): JSONArray {
        val result = JSONArray(runs.toString())
        for (i in 0 until result.length()) {
            val run = result.getJSONObject(i)
            run.remove("targets")
            val owners = run.getJSONObject("owners")
            for (platformKey in listOf("epic", "fab")) {
                if (owners.has(platformKey)) owners.getJSONObject(platformKey).remove("id")
            }
        }
        return result
    }

    companion object {
        @Throws(JSONException::class)
        private fun validateRun(run: JSONObject) {
            run.getString("id")
            run.getLong("startedAt")
            run.getString("country")
            run.getBoolean("all")
            if (run.getString("state") !in listOf("running", "paused", "done")) throw JSONException("领取记录状态无效")
            run.getJSONArray("targets")
            run.getJSONObject("planned")
            run.getJSONObject("errors")
            val owners = run.getJSONObject("owners")
            for (platformKey in listOf("epic", "fab")) if (owners.has(platformKey)) {
                owners.getJSONObject(platformKey).getString("id")
                owners.getJSONObject(platformKey).getString("name")
            }
            val categories = run.getJSONArray("categories")
            for (i in 0 until categories.length())
                if (categories.getString(i) !in listOf("pc", "android", "fab")) throw JSONException("领取记录分类无效")
            val entries = run.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                entry.getString("key")
                entry.getString("title")
                entry.getString("reason")
                if (entry.getString("category") !in listOf("pc", "android", "fab") ||
                    entry.getString("status") !in listOf("pending", "working", "claimed", "owned", "skipped", "failed", "uncertain"))
                    throw JSONException("领取项目记录无效")
            }
        }

        private fun platform(category: String): String = if (category == "fab") "fab" else "epic"

        @Throws(JSONException::class)
        private fun find(catalogue: JSONArray, key: String): JSONObject? {
            for (i in 0 until catalogue.length()) {
                val item = catalogue.getJSONObject(i)
                if (key == item.getString("key")) return item
            }
            return null
        }

        @Throws(JSONException::class)
        private fun update(entry: JSONObject, status: String, reason: String) {
            entry.put("status", status).put("reason", reason).put("updatedAt", System.currentTimeMillis())
        }
    }
}
