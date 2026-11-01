package cn.run.freepocket.data

import cn.run.freepocket.ClaimJournal
import org.json.JSONArray
import org.json.JSONObject

/** External I/O seam; JSON remains here for compatibility with persisted 1.x records and website messages. */
internal interface PocketBackend {
    var items: JSONArray
    var updated: JSONObject
    var fabAccount: JSONObject
    var country: String
    var fabTier: String
    var journalError: String
    val journal: ClaimJournal?
    val initialMessage: String
    val resetPending: Boolean
    val epic: EpicAccess
    suspend fun fetchPcPromotions(country: String): String
    fun scriptSource(): String
    fun saveCache()
    fun saveJournal(): Boolean
    fun setResetPending(pending: Boolean): Boolean
}

internal data class EpicOwnership(val session: JSONObject, val snapshot: JSONObject)
internal interface EpicAccess {
    fun connected(): Boolean
    fun displayName(): String
    fun accountId(): String
    fun cachedSnapshot(): JSONObject?
    fun disconnect()
    fun save(value: JSONObject)
    fun authorizationUrl(): String
    suspend fun exchange(code: String): JSONObject
    suspend fun readOwnership(items: JSONArray): EpicOwnership
}
