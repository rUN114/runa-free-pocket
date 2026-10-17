package cn.run.freepocket;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** Local Epic authorization and complete, paginated ownership reads. Never handles account passwords. */
final class EpicSession {
    // Public launcher client configuration used by open-source Epic clients; not a user credential.
    private static final String CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a";
    private static final String CLIENT_CONFIGURATION = CLIENT_ID + ":daafbccc737745039dffe53d94fc76cf";
    private static final String TOKEN_URL = "https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token";
    private static final String KEY_ALIAS = "freepocket.epic.session.v1";
    private final Context context;
    private JSONObject saved;

    EpicSession(Context context) throws GeneralSecurityException, IOException, JSONException {
        this.context = context;
        String encoded = context.getSharedPreferences("epic-auth", Context.MODE_PRIVATE).getString("encrypted", null);
        if (encoded != null) {
            byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
            if (blob.length < 29) throw new GeneralSecurityException("Invalid encrypted session");
            byte[] iv = java.util.Arrays.copyOfRange(blob, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            saved = new JSONObject(new String(cipher.doFinal(blob, 12, blob.length - 12), StandardCharsets.UTF_8));
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }

    boolean connected() { return saved != null; }
    String displayName() { return saved == null ? "未连接 Epic" : saved.optString("displayName", "Epic 账号"); }
    JSONObject cachedSnapshot() { return saved == null ? null : saved.optJSONObject("snapshot"); }

    void disconnect() {
        saved = null;
        context.getSharedPreferences("epic-auth", Context.MODE_PRIVATE).edit().remove("encrypted").apply();
    }

    void save(JSONObject value) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, blob, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, blob, cipher.getIV().length, encrypted.length);
        context.getSharedPreferences("epic-auth", Context.MODE_PRIVATE).edit()
                .putString("encrypted", Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
        saved = value;
    }

    static String authorizationUrl() {
        return "https://www.epicgames.com/id/login?lang=zh-CN&redirectUrl=" + encode(
                "https://www.epicgames.com/id/api/redirect?clientId=" + CLIENT_ID + "&responseType=code");
    }

    static JSONObject exchange(String code) throws IOException, JSONException {
        return token("authorization_code", "code", code);
    }

    private static JSONObject token(String grant, String field, String value) throws IOException, JSONException {
        String basic = "Basic " + Base64.encodeToString(CLIENT_CONFIGURATION.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        JSONObject response = new JSONObject(request(TOKEN_URL, basic,
                "grant_type=" + grant + "&" + field + "=" + encode(value) + "&token_type=eg1"));
        String account = response.getString("account_id");
        if (!account.matches("[a-fA-F0-9]{32}")) throw new JSONException("Epic 返回的账号标识无效");
        String access = response.getString("access_token");
        String refresh = response.getString("refresh_token");
        long lifetime = response.getLong("expires_in");
        if (access.isEmpty() || refresh.isEmpty() || lifetime <= 0) throw new JSONException("Epic 未返回有效会话");
        return new JSONObject().put("accountId", account).put("accessToken", access).put("refreshToken", refresh)
                .put("displayName", response.optString("displayName", "Epic 账号"))
                .put("expiresAt", System.currentTimeMillis() + lifetime * 1000L);
    }

    static final class Ownership {
        final JSONObject session;
        final JSONObject snapshot;
        Ownership(JSONObject session, JSONObject snapshot) { this.session = session; this.snapshot = snapshot; }
    }

    Ownership readOwnership(JSONArray requestedItems) throws IOException, JSONException {
        if (saved == null) throw new IOException("请先连接 Epic 账号");
        JSONObject session = new JSONObject(saved.toString());
        String accountId = session.getString("accountId");
        if (session.getLong("expiresAt") < System.currentTimeMillis() + 60000) {
            session = token("refresh_token", "refresh_token", session.getString("refreshToken"));
            if (!accountId.equals(session.getString("accountId"))) throw new IOException("账号身份发生变化，请重新连接");
        }
        String authorization = "Bearer " + session.getString("accessToken");
        JSONArray owned = new JSONArray();
        List<EpicEntitlements.Grant> grants = EpicEntitlements.readAll((start, count) -> {
            String url = "https://entitlement-public-service-prod08.ol.epicgames.com/entitlement/api/account/"
                    + accountId + "/entitlements?start=" + start + "&count=" + count;
            try {
                JSONArray batch = new JSONArray(request(url, authorization, null));
                List<EpicEntitlements.Grant> page = new ArrayList<>();
                for (int i = 0; i < batch.length(); i++) {
                    JSONObject entry = batch.getJSONObject(i);
                    page.add(new EpicEntitlements.Grant(entry.getString("namespace"), entry.getString("catalogItemId"), entry.getBoolean("active")));
                }
                return page;
            } catch (JSONException error) { throw new IOException("拥有凭证格式已变化，未发布不完整结果", error); }
        });
        for (EpicEntitlements.Grant grant : grants) owned.put(new JSONObject().put("namespace", grant.namespace).put("id", grant.id));
        // Direct entitlements omit inherited grants. Ask Epic for the exact catalog permissions too.
        Set<String> direct = new LinkedHashSet<>();
        for (EpicEntitlements.Grant grant : grants) direct.add(grant.namespace + ":" + grant.id);
        Set<String> missing = new LinkedHashSet<>();
        for (int i = 0; i < requestedItems.length(); i++) {
            JSONArray ids = requestedItems.getJSONObject(i).optJSONArray("catalogItems");
            if (ids == null) continue;
            for (int j = 0; j < ids.length(); j++) {
                JSONObject id = ids.getJSONObject(j);
                String catalogKey = id.getString("namespace") + ":" + id.getString("id");
                if (!catalogKey.matches("[a-zA-Z0-9_-]{1,100}:[a-zA-Z0-9_-]{1,100}")) throw new JSONException("商品权限标识无效");
                if (!direct.contains(catalogKey)) missing.add(catalogKey);
            }
        }
        JSONArray checks = new JSONArray();
        List<String> pending = new ArrayList<>(missing);
        for (int offset = 0; offset < pending.size(); offset += 20) {
            List<String> batch = pending.subList(offset, Math.min(offset + 20, pending.size()));
            JSONArray verified = queryCatalogOwnership(accountId, authorization, batch);
            for (int i = 0; i < verified.length(); i++) {
                JSONObject entry = verified.getJSONObject(i);
                checks.put(entry);
                if (entry.getBoolean("owned")) owned.put(new JSONObject().put("namespace", entry.getString("namespace")).put("id", entry.getString("id")));
            }
        }
        JSONObject snapshot = new JSONObject().put("accountId", accountId)
                .put("checkedAt", System.currentTimeMillis()).put("owned", owned).put("checks", checks);
        session.put("snapshot", snapshot);
        return new Ownership(session, snapshot);
    }

    private static JSONArray queryCatalogOwnership(String accountId, String authorization, List<String> ids) throws IOException, JSONException {
        StringBuilder form = new StringBuilder();
        for (String id : ids) {
            if (form.length() > 0) form.append('&');
            form.append("nsCatalogItemId=").append(encode(id));
        }
        String endpoint = "https://ecommerceintegration-public-service-ecomprod02.ol.epicgames.com/ecommerceintegration/api/public/platforms/EPIC/identities/"
                + accountId + "/ownershipToken";
        JSONObject response = new JSONObject(request(endpoint, authorization, form.toString()));
        return parseOwnershipProof(response.getString("token"), accountId, ids, System.currentTimeMillis());
    }

    static JSONArray parseOwnershipProof(String token, String accountId, List<String> ids, long now) throws JSONException {
        if (token.startsWith("egoc1~")) token = token.substring(6);
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new JSONException("Epic 商品权限响应无效");
        // This token comes directly from Epic over verified HTTPS, never from a page or another client.
        JSONObject proof;
        try { proof = new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException error) { throw new JSONException("Epic 商品权限编码无效"); }
        if (!accountId.equals(proof.getString("sub")) || proof.getLong("exp") * 1000 <= now)
            throw new JSONException("Epic 商品权限的账号或有效期不匹配");
        JSONArray entries = proof.getJSONArray("ent");
        JSONArray result = new JSONArray();
        Set<String> received = new LinkedHashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String namespace = entry.getString("namespace"), id = entry.getString("catalogItemId");
            String key = namespace + ":" + id;
            if (!ids.contains(key) || !received.add(key) || !(entry.get("owned") instanceof Boolean))
                throw new JSONException("Epic 商品权限与请求商品不匹配");
            result.put(new JSONObject().put("namespace", namespace).put("id", id).put("owned", entry.getBoolean("owned")));
        }
        // Epic documents an empty ent array when none of the requested items are owned.
        // Only this directly requested, account-bound proof gives absent entries a negative meaning.
        for (String requested : ids) {
            if (received.contains(requested)) continue;
            String[] identity = requested.split(":", 2);
            result.put(new JSONObject().put("namespace", identity[0]).put("id", identity[1]).put("owned", false));
        }
        return result;
    }

    private static String encode(String value) { return android.net.Uri.encode(value); }

    private static String request(String endpoint, String authorization, String form) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", authorization);
        try {
            if (form != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (var output = connection.getOutputStream()) { output.write(form.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new IOException("Epic 授权无效或权限不足（HTTP " + status + "），请重新连接账号");
            if (status != 200) throw new IOException("Epic 请求失败（HTTP " + status + "）");
            try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 4 * 1024 * 1024) throw new IOException("Epic 响应过大，已停止同步");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }
}
