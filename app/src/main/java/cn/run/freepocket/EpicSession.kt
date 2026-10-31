package cn.run.freepocket

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashSet
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local Epic authorization and complete, paginated ownership reads. Never handles account passwords. */
internal class EpicSession @Throws(GeneralSecurityException::class, IOException::class, JSONException::class)
constructor(private val context: Context) {
    private var saved: JSONObject? = null

    init {
        val encoded = context.getSharedPreferences("epic-auth", Context.MODE_PRIVATE).getString("encrypted", null)
        if (encoded != null) {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            if (blob.size < 29) throw GeneralSecurityException("Invalid encrypted session")
            val iv = Arrays.copyOfRange(blob, 0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            saved = JSONObject(String(cipher.doFinal(blob, 12, blob.size - 12), StandardCharsets.UTF_8))
        }
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            return generator.generateKey()
        }
        return store.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun connected(): Boolean = saved != null

    fun accountId(): String = saved?.optString("accountId") ?: ""

    fun displayName(): String = saved?.optString("displayName", "Epic 账号") ?: "未连接 Epic"

    fun cachedSnapshot(): JSONObject? = saved?.optJSONObject("snapshot")

    fun disconnect() {
        saved = null
        context.getSharedPreferences("epic-auth", Context.MODE_PRIVATE).edit().remove("encrypted").apply()
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun save(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toString().toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        val blob = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(encrypted, 0, blob, iv.size, encrypted.size)
        context.getSharedPreferences("epic-auth", Context.MODE_PRIVATE).edit()
            .putString("encrypted", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        saved = value
    }

    class Ownership(val session: JSONObject, val snapshot: JSONObject)

    @Throws(IOException::class, JSONException::class)
    fun readOwnership(requestedItems: JSONArray): Ownership {
        val stored = saved ?: throw IOException("请先连接 Epic 账号")
        var session = JSONObject(stored.toString())
        val accountId = session.getString("accountId")
        if (session.getLong("expiresAt") < System.currentTimeMillis() + 60000) {
            session = token("refresh_token", "refresh_token", session.getString("refreshToken"))
            if (accountId != session.getString("accountId")) throw IOException("账号身份发生变化，请重新连接")
        }
        val authorization = "Bearer " + session.getString("accessToken")
        val owned = JSONArray()
        val grants = EpicEntitlements.readAll { start, count ->
            val url = "https://entitlement-public-service-prod08.ol.epicgames.com/entitlement/api/account/" +
                accountId + "/entitlements?start=" + start + "&count=" + count
            try {
                val batch = JSONArray(request(url, authorization, null))
                val page = ArrayList<EpicEntitlements.Grant>()
                for (i in 0 until batch.length()) {
                    val entry = batch.getJSONObject(i)
                    page.add(EpicEntitlements.Grant(entry.getString("namespace"), entry.getString("catalogItemId"), entry.getBoolean("active")))
                }
                page
            } catch (error: JSONException) {
                throw IOException("拥有凭证格式已变化，未发布不完整结果", error)
            }
        }
        for (grant in grants) owned.put(JSONObject().put("namespace", grant.namespace).put("id", grant.id))
        // Direct entitlements omit inherited grants. Ask Epic for the exact catalog permissions too.
        val direct = LinkedHashSet<String>()
        for (grant in grants) direct.add(grant.namespace + ":" + grant.id)
        val missing = LinkedHashSet<String>()
        for (i in 0 until requestedItems.length()) {
            val ids = requestedItems.getJSONObject(i).optJSONArray("catalogItems") ?: continue
            for (j in 0 until ids.length()) {
                val id = ids.getJSONObject(j)
                val catalogKey = id.getString("namespace") + ":" + id.getString("id")
                if (!catalogKey.matches("[a-zA-Z0-9_-]{1,100}:[a-zA-Z0-9_-]{1,100}".toRegex())) throw JSONException("商品权限标识无效")
                if (catalogKey !in direct) missing.add(catalogKey)
            }
        }
        val checks = JSONArray()
        val pending = ArrayList(missing)
        var offset = 0
        while (offset < pending.size) {
            val batch = pending.subList(offset, minOf(offset + 20, pending.size))
            val verified = queryCatalogOwnership(accountId, authorization, batch)
            for (i in 0 until verified.length()) {
                val entry = verified.getJSONObject(i)
                checks.put(entry)
                if (entry.getBoolean("owned"))
                    owned.put(JSONObject().put("namespace", entry.getString("namespace")).put("id", entry.getString("id")))
            }
            offset += 20
        }
        val snapshot = JSONObject().put("accountId", accountId)
            .put("checkedAt", System.currentTimeMillis()).put("owned", owned).put("checks", checks)
        session.put("snapshot", snapshot)
        return Ownership(session, snapshot)
    }

    companion object {
        // Public launcher client configuration used by open-source Epic clients; not a user credential.
        private const val CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a"
        private const val CLIENT_CONFIGURATION = "$CLIENT_ID:daafbccc737745039dffe53d94fc76cf"
        private const val TOKEN_URL = "https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token"
        private const val KEY_ALIAS = "freepocket.epic.session.v1"

        @JvmStatic
        fun authorizationUrl(): String =
            "https://www.epicgames.com/id/login?lang=zh-CN&redirectUrl=" + encode(
                "https://www.epicgames.com/id/api/redirect?clientId=" + CLIENT_ID + "&responseType=code")

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        fun exchange(code: String): JSONObject = token("authorization_code", "code", code)

        @Throws(IOException::class, JSONException::class)
        private fun token(grant: String, field: String, value: String): JSONObject {
            val basic = "Basic " + Base64.encodeToString(CLIENT_CONFIGURATION.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            val response = JSONObject(request(TOKEN_URL, basic,
                "grant_type=" + grant + "&" + field + "=" + encode(value) + "&token_type=eg1"))
            val account = response.getString("account_id")
            if (!account.matches("[a-fA-F0-9]{32}".toRegex())) throw JSONException("Epic 返回的账号标识无效")
            val access = response.getString("access_token")
            val refresh = response.getString("refresh_token")
            val lifetime = response.getLong("expires_in")
            if (access.isEmpty() || refresh.isEmpty() || lifetime <= 0) throw JSONException("Epic 未返回有效会话")
            return JSONObject().put("accountId", account).put("accessToken", access).put("refreshToken", refresh)
                .put("displayName", response.optString("displayName", "Epic 账号"))
                .put("expiresAt", System.currentTimeMillis() + lifetime * 1000L)
        }

        @Throws(IOException::class, JSONException::class)
        private fun queryCatalogOwnership(accountId: String, authorization: String, ids: List<String>): JSONArray {
            val form = StringBuilder()
            for (id in ids) {
                if (form.isNotEmpty()) form.append('&')
                form.append("nsCatalogItemId=").append(encode(id))
            }
            val endpoint = "https://ecommerceintegration-public-service-ecomprod02.ol.epicgames.com/ecommerceintegration/api/public/platforms/EPIC/identities/" +
                accountId + "/ownershipToken"
            val response = JSONObject(request(endpoint, authorization, form.toString()))
            return parseOwnershipProof(response.getString("token"), accountId, ids, System.currentTimeMillis())
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun parseOwnershipProof(proofToken: String, accountId: String, ids: List<String>, now: Long): JSONArray {
            var token = proofToken
            if (token.startsWith("egoc1~")) token = token.substring(6)
            val parts = token.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
            if (parts.size != 3) throw JSONException("Epic 商品权限响应无效")
            // This token comes directly from Epic over verified HTTPS, never from a page or another client.
            val proof = try {
                JSONObject(String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
            } catch (error: IllegalArgumentException) {
                throw JSONException("Epic 商品权限编码无效")
            }
            if (accountId != proof.getString("sub") || proof.getLong("exp") * 1000 <= now)
                throw JSONException("Epic 商品权限的账号或有效期不匹配")
            val entries = proof.getJSONArray("ent")
            val result = JSONArray()
            val received = LinkedHashSet<String>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val namespace = entry.getString("namespace")
                val id = entry.getString("catalogItemId")
                val key = namespace + ":" + id
                if (key !in ids || !received.add(key) || entry.get("owned") !is Boolean)
                    throw JSONException("Epic 商品权限与请求商品不匹配")
                result.put(JSONObject().put("namespace", namespace).put("id", id).put("owned", entry.getBoolean("owned")))
            }
            // Epic documents an empty ent array when none of the requested items are owned.
            // Only this directly requested, account-bound proof gives absent entries a negative meaning.
            for (requested in ids) {
                if (requested in received) continue
                val identity = requested.split(":", limit = 2)
                result.put(JSONObject().put("namespace", identity[0]).put("id", identity[1]).put("owned", false))
            }
            return result
        }

        private fun encode(value: String): String = android.net.Uri.encode(value)

        @Throws(IOException::class)
        private fun request(endpoint: String, authorization: String, form: String?): String {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 25000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", authorization)
            try {
                if (form != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { output -> output.write(form.toByteArray(StandardCharsets.UTF_8)) }
                }
                val status = connection.responseCode
                if (status == 401 || status == 403) throw IOException("Epic 授权无效或权限不足（HTTP $status），请重新连接账号")
                if (status != 200) throw IOException("Epic 请求失败（HTTP $status）")
                connection.inputStream.use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (output.size() + count > 4 * 1024 * 1024) throw IOException("Epic 响应过大，已停止同步")
                            output.write(buffer, 0, count)
                        }
                        return output.toString(StandardCharsets.UTF_8.name())
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
