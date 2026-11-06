package cn.run.freepocket.data

import android.content.Context
import cn.run.freepocket.ClaimJournal
import cn.run.freepocket.EpicPublicApi
import cn.run.freepocket.EpicSession
import cn.run.freepocket.model.CatalogItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class AndroidPocketBackend(context: Context) : PocketBackend {
    private val application = context.applicationContext
    private val cache = application.getSharedPreferences("catalogue", Context.MODE_PRIVATE)
    private val journalPreferences = application.getSharedPreferences("claim-journal", Context.MODE_PRIVATE)
    override var items = JSONArray()
    override var updated = JSONObject()
    override var fabAccount = JSONObject()
    override var country = cache.getString("country", "CN")!!
    override var fabTier = cache.getString("fabTier", "")!!
    override var journalError = ""
    override val journal: ClaimJournal?
    override var initialMessage = "正在读取本期免费内容…"
        private set
    override val resetPending get() = cache.getBoolean("accountResetPending", false)
    override val epic: EpicAccess
    private val automation by lazy {
        application.assets.open("domain.js").bufferedReader().use { it.readText() } + "\n" +
            application.assets.open("automation.js").bufferedReader().use { it.readText() }
    }

    init {
        try {
            fabAccount = JSONObject(cache.getString("fabAccount", "{}")!!)
            if (cache.getInt("schema", 1) == 3) {
                val loaded = JSONArray(cache.getString("items", "[]")!!)
                for (index in 0 until loaded.length()) {
                    val item = loaded.getJSONObject(index)
                    CatalogItem.fromJson(item)
                    item.put("status", "unknown").put("cached", true)
                }
                items = loaded
                updated = JSONObject(cache.getString("updated", "{}")!!)
            }
        } catch (_: JSONException) {
            initialMessage = "活动缓存无法读取，请重新刷新。"
        } catch (_: IllegalArgumentException) {
            initialMessage = "活动缓存字段无法识别，请重新刷新。"
        }
        var session: EpicSession
        try {
            session = EpicSession(application)
        } catch (_: Exception) {
            application.getSharedPreferences("epic-auth", Context.MODE_PRIVATE).edit().remove("encrypted").apply()
            initialMessage = "本机授权记录无法读取，请重新连接 Epic 账号。"
            session = EpicSession(application)
        }
        epic = AndroidEpicAccess(session)
        journal = try {
            ClaimJournal(journalPreferences.getString("data", "{}")!!)
        } catch (_: JSONException) {
            journalError = "领取记录无法读取，为防止重复订单已停用自动领取，请保留应用数据并检查记录。"
            null
        }
    }

    override suspend fun fetchPcPromotions(country: String): String = withContext(Dispatchers.IO) {
        EpicPublicApi.promotions(country)
    }
    override fun scriptSource(): String = automation
    override fun saveCache() {
        cache.edit().putInt("schema", 3).putString("items", items.toString())
            .putString("updated", updated.toString()).putString("country", country)
            .putString("fabTier", fabTier).putString("fabAccount", fabAccount.toString()).apply()
    }
    // Deliberate durability barrier before page permits. Device cost and the retained
    // main-thread trade-off are recorded in docs/NATIVE-REVIEW-2026-11-01.md.
    override fun saveJournal(): Boolean = journalPreferences.edit()
        .putString("data", checkNotNull(journal).serialize()).commit()
    override fun setResetPending(pending: Boolean): Boolean = cache.edit()
        .putBoolean("accountResetPending", pending).commit()
}

private class AndroidEpicAccess(private val session: EpicSession) : EpicAccess {
    override fun connected() = session.connected()
    override fun displayName() = session.displayName()
    override fun accountId() = session.accountId()
    override fun cachedSnapshot() = session.cachedSnapshot()
    override fun disconnect() = session.disconnect()
    override fun save(value: JSONObject) = session.save(value)
    override fun authorizationUrl() = EpicSession.authorizationUrl()
    override suspend fun exchange(code: String): JSONObject = withContext(Dispatchers.IO) { EpicSession.exchange(code) }
    override suspend fun readOwnership(items: JSONArray): EpicOwnership = withContext(Dispatchers.IO) {
        val result = session.readOwnership(items)
        EpicOwnership(result.session, result.snapshot)
    }
}
