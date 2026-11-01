package cn.run.freepocket.claim

import cn.run.freepocket.model.CatalogItem
import cn.run.freepocket.model.Category
import org.json.JSONObject

internal enum class TaskMode(val wire: String) {
    DISCOVER("discover"), ENSURE_AUTH("ensureAuth"), AUTH("auth"), AUTHORIZING("authorizing"),
    SYNC_EPIC("syncEpic"), SYNC_FAB("syncFab"), PLAN("planBatch"), CLAIM("claim"), CHECK("check"), VERIFY_EPIC("verifyEpic")
}

internal data class ClaimTask(
    var mode: TaskMode,
    val category: Category,
    var item: CatalogItem? = null,
    val forClaim: Boolean = false,
    val precheck: Boolean = false,
    var nonce: String = "",
    var readRetry: Int = 0,
    var verifyAttempt: Int = 0,
    var fabTier: String = "",
    var fabTierVerified: Boolean = false,
    var accountId: String = ""
) {
    fun browserPayload(country: String): JSONObject = JSONObject()
        .put("mode", mode.wire).put("category", category.wire).put("country", country)
        .put("nonce", nonce).put("readRetry", readRetry).put("precheck", precheck).apply {
            item?.let { put("item", it.toJson()) }
            if (accountId.isNotEmpty()) put("accountId", accountId)
            if (fabTier.isNotEmpty()) put("fabTier", fabTier)
            if (fabTierVerified) put("fabTierVerified", true)
        }
}
