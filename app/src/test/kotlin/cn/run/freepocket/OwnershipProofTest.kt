package cn.run.freepocket

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class OwnershipProofTest {
    private fun token(account: String, expiration: Long, entries: JSONArray): String {
        val body = JSONObject().put("sub", account).put("exp", expiration).put("ent", entries)
        return "egoc1~e30." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(body.toString().toByteArray(StandardCharsets.UTF_8)) + ".server-signature"
    }

    private fun grant(id: String, owned: Any?): JSONObject =
        JSONObject().put("namespace", "ns").put("catalogItemId", id).put("owned", owned)

    @Test
    fun inheritedMobileOwnershipComesFromTheRequestedCatalogProof() {
        val result = EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300,
            JSONArray().put(grant("android", true))), "account", listOf("ns:android"), NOW)
        assertTrue(result.getJSONObject(0).getBoolean("owned"))
        assertEquals("android", result.getJSONObject(0).getString("id"))
    }

    @Test
    fun emptyProofIsAnExplicitScopedNegativePerEpicProtocol() {
        val result = EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300, JSONArray()),
            "account", listOf("ns:android"), NOW)
        assertEquals(1, result.length())
        assertFalse(result.getJSONObject(0).getBoolean("owned"))
    }

    @Test
    fun wrongAccountExpiredMalformedAndUnexpectedItemsFail() {
        assertThrows(JSONException::class.java) {
            EpicSession.parseOwnershipProof(token("other", NOW / 1000 + 300, JSONArray()), "account", listOf("ns:android"), NOW)
        }
        assertThrows(JSONException::class.java) {
            EpicSession.parseOwnershipProof(token("account", NOW / 1000, JSONArray()), "account", listOf("ns:android"), NOW)
        }
        assertThrows(JSONException::class.java) {
            EpicSession.parseOwnershipProof("egoc1~e30.%%%bad.sig", "account", listOf("ns:android"), NOW)
        }
        assertThrows(JSONException::class.java) {
            EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300,
                JSONArray().put(grant("pc", true))), "account", listOf("ns:android"), NOW)
        }
        assertThrows(JSONException::class.java) {
            EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300,
                JSONArray().put(grant("android", "true"))), "account", listOf("ns:android"), NOW)
        }
    }

    @Test
    fun duplicateAndIncompleteProtocolFieldsAreRejected() {
        val duplicate = JSONArray().put(grant("android", true)).put(grant("android", false))
        assertThrows(JSONException::class.java) {
            EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300, duplicate), "account", listOf("ns:android"), NOW)
        }
        assertThrows(JSONException::class.java) {
            EpicSession.parseOwnershipProof("e30.e30.sig", "account", listOf("ns:android"), NOW)
        }
    }

    @Test
    fun nativeQueueRejectsFutureExpiredOwnedAndInvalidDates() {
        assertFalse(cn.run.freepocket.data.LegacyCatalogState.claimableNow(JSONObject().put("availability", "upcoming"), NOW))
        assertFalse(cn.run.freepocket.data.LegacyCatalogState.claimableNow(JSONObject().put("start", "2099-01-01T00:00:00Z"), NOW))
        assertFalse(cn.run.freepocket.data.LegacyCatalogState.claimableNow(JSONObject().put("start", NOW + 1000), NOW))
        assertFalse(cn.run.freepocket.data.LegacyCatalogState.claimableNow(JSONObject().put("end", "2000-01-01T00:00:00Z"), NOW))
        assertFalse(cn.run.freepocket.data.LegacyCatalogState.claimableNow(JSONObject().put("start", "invalid"), NOW))
        assertFalse(cn.run.freepocket.data.LegacyCatalogState.claimableNow(JSONObject().put("status", "owned"), NOW))
        assertTrue(cn.run.freepocket.data.LegacyCatalogState.claimableNow(JSONObject().put("start", NOW - 1000).put("end", "2099-01-01T00:00:00Z"), NOW))
    }

    @Test
    fun switchingAccountClearsAllOwnershipEvidenceButRetainsPublicCatalogue() {
        val items = JSONArray()
        for (category in listOf("pc", "android", "fab")) {
            items.put(JSONObject().put("key", "$category:product").put("category", category)
                .put("status", "owned").put("checkedAt", NOW).put("cached", true)
                .put("statusReason", "Previous account proof").put("cover", "https://cdn1.epicgames.com/cover.jpg")
                .put("availability", "upcoming"))
        }
        cn.run.freepocket.data.LegacyCatalogState.invalidateOwnership(items, "all")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            assertEquals("unknown", item.getString("status"))
            assertFalse(item.has("checkedAt"))
            assertFalse(item.has("cached"))
            assertFalse(item.has("statusReason"))
            assertEquals("upcoming", item.getString("availability"))
            assertEquals("https://cdn1.epicgames.com/cover.jpg", item.getString("cover"))
            assertTrue(item.has("key"))
        }
    }

    @Test
    fun ordinaryEpicReauthorizationClearsPcAndAndroidWithoutChangingFabProof() {
        val items = JSONArray()
        for (category in listOf("pc", "android", "fab"))
            items.put(JSONObject().put("category", category).put("status", "owned").put("checkedAt", NOW).put("cached", true))
        cn.run.freepocket.data.LegacyCatalogState.invalidateOwnership(items, "epic")
        assertEquals("unknown", items.getJSONObject(0).getString("status"))
        assertEquals("unknown", items.getJSONObject(1).getString("status"))
        assertEquals("owned", items.getJSONObject(2).getString("status"))
        assertEquals(NOW, items.getJSONObject(2).getLong("checkedAt"))
    }

    companion object {
        private const val NOW = 1800000000000L
    }
}
