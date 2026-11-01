package cn.run.freepocket

import org.json.JSONException
import org.json.JSONObject

/** Browser-supplied origins and frame identity are checked independently of message content. */
internal object StoreMessagePolicy {
    private const val STORE = "https://store.epicgames.com"
    private const val EPIC = "https://www.epicgames.com"
    private const val FAB = "https://www.fab.com"
    val origins: Set<String> = setOf(STORE, EPIC, FAB)

    fun accepts(category: String, mode: String, data: JSONObject, origin: String, main: Boolean): Boolean {
        val product = if (category == "fab") FAB else STORE
        val productPage = main && origin == product
        // Epic checkout may run in a cross-origin iframe; Fab also has its own payment frame.
        // The path is a routing hint from our injected script; origin/main still come from WebView.
        val storeCheckoutFrame = category != "fab" && origin == STORE && data.optString("path") == "/checkout"
        val checkout = productPage || storeCheckoutFrame || origin == EPIC || (category == "fab" && origin == FAB)
        val login = main && (origin == product || origin == EPIC)
        return when (data.opt("type")) {
            "actionReady" -> mode == "claim" && when (data.opt("stage")) {
                "product" -> productPage
                "submit" -> checkout
                else -> false
            }
            "licenseInvalidated" -> category == "fab" && mode == "claim" && productPage
            "authorized" -> category != "fab" && mode == "auth" && main && origin == EPIC
            "authenticated" -> category == "fab" && mode == "auth" && productPage
            "ownership" -> category == "fab" && mode == "syncFab" && productPage
            "catalogue" -> mode == "discover" && productPage
            "result" -> mode in listOf("claim", "check") && productPage
            "checkoutComplete", "ordered", "blocked" -> mode == "claim" && checkout
            "waiting", "error" -> when (mode) {
                "auth" -> login
                "discover", "syncFab" -> productPage
                "claim", "check" -> checkout
                else -> false
            }
            else -> false
        }
    }

    @Throws(JSONException::class)
    fun validate(category: String, data: JSONObject) {
        when (data.getString("type")) {
            "actionReady" -> {
                val stage = data.getString("stage")
                if (category == "fab" && stage == "product") data.getString("fabTier")
            }
            "authorized" -> data.getString("code")
            "authenticated" -> data.getString("displayName")
            "result" -> if (data.getString("status") !in listOf("owned", "unowned", "expired"))
                throw JSONException("Unknown ownership status")
            "waiting", "error", "blocked" -> data.getString("message")
            "ownership" -> {
                data.getString("accountId")
                data.getString("displayName")
                data.getLong("checkedAt")
                val owned = data.getJSONArray("owned")
                for (i in 0 until owned.length()) owned.getString(i)
            }
            "catalogue" -> {
                val items = data.getJSONArray("items")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    for (field in listOf("key", "category", "title", "url")) item.getString(field)
                }
            }
        }
    }
}
