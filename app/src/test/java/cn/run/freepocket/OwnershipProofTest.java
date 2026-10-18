package cn.run.freepocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import static org.junit.Assert.*;

public class OwnershipProofTest {
    private static final long NOW = 1800000000000L;
    private String token(String account, long expiration, JSONArray entries) throws JSONException {
        JSONObject body = new JSONObject().put("sub", account).put("exp", expiration).put("ent", entries);
        return "egoc1~e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8)) + ".server-signature";
    }
    private JSONObject grant(String id, Object owned) throws JSONException {
        return new JSONObject().put("namespace", "ns").put("catalogItemId", id).put("owned", owned);
    }
    @Test public void inheritedMobileOwnershipComesFromTheRequestedCatalogProof() throws Exception {
        JSONArray result = EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300,
                new JSONArray().put(grant("android", true))), "account", List.of("ns:android"), NOW);
        assertTrue(result.getJSONObject(0).getBoolean("owned"));
        assertEquals("android", result.getJSONObject(0).getString("id"));
    }
    @Test public void emptyProofIsAnExplicitScopedNegativePerEpicProtocol() throws Exception {
        JSONArray result = EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300, new JSONArray()),
                "account", List.of("ns:android"), NOW);
        assertEquals(1, result.length());
        assertFalse(result.getJSONObject(0).getBoolean("owned"));
    }
    @Test public void wrongAccountExpiredMalformedAndUnexpectedItemsFail() throws Exception {
        assertThrows(JSONException.class, () -> EpicSession.parseOwnershipProof(token("other", NOW / 1000 + 300, new JSONArray()), "account", List.of("ns:android"), NOW));
        assertThrows(JSONException.class, () -> EpicSession.parseOwnershipProof(token("account", NOW / 1000, new JSONArray()), "account", List.of("ns:android"), NOW));
        assertThrows(JSONException.class, () -> EpicSession.parseOwnershipProof("egoc1~e30.%%%bad.sig", "account", List.of("ns:android"), NOW));
        assertThrows(JSONException.class, () -> EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300,
                new JSONArray().put(grant("pc", true))), "account", List.of("ns:android"), NOW));
        assertThrows(JSONException.class, () -> EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300,
                new JSONArray().put(grant("android", "true"))), "account", List.of("ns:android"), NOW));
    }
    @Test public void duplicateAndIncompleteProtocolFieldsAreRejected() throws Exception {
        JSONArray duplicate = new JSONArray().put(grant("android", true)).put(grant("android", false));
        assertThrows(JSONException.class, () -> EpicSession.parseOwnershipProof(token("account", NOW / 1000 + 300, duplicate), "account", List.of("ns:android"), NOW));
        assertThrows(JSONException.class, () -> EpicSession.parseOwnershipProof("e30.e30.sig", "account", List.of("ns:android"), NOW));
    }
    @Test public void nativeQueueRejectsFutureExpiredOwnedAndInvalidDates() throws Exception {
        assertFalse(MainActivity.claimableNow(new JSONObject().put("availability", "upcoming"), NOW));
        assertFalse(MainActivity.claimableNow(new JSONObject().put("start", "2099-01-01T00:00:00Z"), NOW));
        assertFalse(MainActivity.claimableNow(new JSONObject().put("start", NOW + 1000), NOW));
        assertFalse(MainActivity.claimableNow(new JSONObject().put("end", "2000-01-01T00:00:00Z"), NOW));
        assertFalse(MainActivity.claimableNow(new JSONObject().put("start", "invalid"), NOW));
        assertFalse(MainActivity.claimableNow(new JSONObject().put("status", "owned"), NOW));
        assertTrue(MainActivity.claimableNow(new JSONObject().put("start", NOW - 1000).put("end", "2099-01-01T00:00:00Z"), NOW));
    }
    @Test public void switchingAccountClearsAllOwnershipEvidenceButRetainsPublicCatalogue() throws Exception {
        JSONArray items = new JSONArray();
        for (String category : List.of("pc", "android", "fab")) {
            items.put(new JSONObject().put("key", category + ":product").put("category", category)
                    .put("status", "owned").put("checkedAt", NOW).put("cached", true)
                    .put("statusReason", "Previous account proof").put("cover", "https://cdn1.epicgames.com/cover.jpg")
                    .put("availability", "upcoming"));
        }
        MainActivity.invalidateOwnership(items, "all");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            assertEquals("unknown", item.getString("status"));
            assertFalse(item.has("checkedAt"));
            assertFalse(item.has("cached"));
            assertFalse(item.has("statusReason"));
            assertEquals("upcoming", item.getString("availability"));
            assertEquals("https://cdn1.epicgames.com/cover.jpg", item.getString("cover"));
            assertTrue(item.has("key"));
        }
    }
    @Test public void ordinaryEpicReauthorizationClearsPcAndAndroidWithoutChangingFabProof() throws Exception {
        JSONArray items = new JSONArray();
        for (String category : List.of("pc", "android", "fab"))
            items.put(new JSONObject().put("category", category).put("status", "owned").put("checkedAt", NOW).put("cached", true));
        MainActivity.invalidateOwnership(items, "epic");
        assertEquals("unknown", items.getJSONObject(0).getString("status"));
        assertEquals("unknown", items.getJSONObject(1).getString("status"));
        assertEquals("owned", items.getJSONObject(2).getString("status"));
        assertEquals(NOW, items.getJSONObject(2).getLong("checkedAt"));
    }
}
