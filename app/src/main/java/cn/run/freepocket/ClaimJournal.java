package cn.run.freepocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Local batch progress. Reservations survive history trimming and are never replayed as orders. */
final class ClaimJournal {
    private final JSONObject root;
    private final JSONArray runs;
    private final JSONObject reservations;

    ClaimJournal(String json) throws JSONException {
        root = new JSONObject(json);
        if (root.length() == 0) {
            root.put("schema", 1).put("runs", new JSONArray()).put("reservations", new JSONObject());
        }
        if (root.getInt("schema") != 1) throw new JSONException("领取记录版本无法识别");
        runs = root.getJSONArray("runs");
        reservations = root.getJSONObject("reservations");
        for (int i = 0; i < runs.length(); i++) validateRun(runs.getJSONObject(i));
        if (current() != null && "running".equals(current().getString("state"))) pause("应用已中断，继续前将重新核实库存");
    }

    private static void validateRun(JSONObject run) throws JSONException {
        run.getString("id");
        run.getLong("startedAt");
        run.getString("country");
        run.getBoolean("all");
        if (!Arrays.asList("running", "paused", "done").contains(run.getString("state"))) throw new JSONException("领取记录状态无效");
        run.getJSONArray("targets");
        run.getJSONObject("planned");
        run.getJSONObject("errors");
        JSONObject owners = run.getJSONObject("owners");
        for (String platform : Arrays.asList("epic", "fab")) if (owners.has(platform)) {
            owners.getJSONObject(platform).getString("id");
            owners.getJSONObject(platform).getString("name");
        }
        JSONArray categories = run.getJSONArray("categories");
        for (int i = 0; i < categories.length(); i++) if (!Arrays.asList("pc", "android", "fab").contains(categories.getString(i))) throw new JSONException("领取记录分类无效");
        JSONArray entries = run.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            entry.getString("key");
            entry.getString("title");
            entry.getString("reason");
            if (!Arrays.asList("pc", "android", "fab").contains(entry.getString("category")) ||
                    !Arrays.asList("pending", "working", "claimed", "owned", "skipped", "failed", "uncertain").contains(entry.getString("status")))
                throw new JSONException("领取项目记录无效");
        }
    }

    JSONObject current() { return runs.optJSONObject(0); }
    String serialize() { return root.toString(); }
    boolean running() { return current() != null && "running".equals(current().optString("state")); }

    void start(String country, List<String> categories, JSONArray selected) throws JSONException {
        JSONObject run = new JSONObject().put("id", UUID.randomUUID().toString()).put("state", "running")
                .put("startedAt", System.currentTimeMillis()).put("country", country)
                .put("categories", new JSONArray(categories)).put("all", selected == null)
                .put("targets", selected == null ? new JSONArray() : selected)
                .put("owners", new JSONObject()).put("planned", new JSONObject())
                .put("errors", new JSONObject()).put("entries", new JSONArray());
        JSONArray retained = new JSONArray().put(run);
        for (int i = 0; i < Math.min(runs.length(), 19); i++) retained.put(runs.getJSONObject(i));
        while (runs.length() > 0) runs.remove(runs.length() - 1);
        for (int i = 0; i < retained.length(); i++) runs.put(retained.getJSONObject(i));
    }

    List<String> categories() throws JSONException {
        List<String> result = new ArrayList<>();
        JSONArray categories = current().getJSONArray("categories");
        for (int i = 0; i < categories.length(); i++) result.add(categories.getString(i));
        return result;
    }

    void resume(String country) throws JSONException {
        if (current() == null) throw new JSONException("没有可继续的领取记录");
        if (!country.equals(current().getString("country"))) throw new JSONException("账号地区已变化，请开始新的领取任务");
        current().put("state", "running");
    }

    private static String platform(String category) { return category.equals("fab") ? "fab" : "epic"; }

    void bind(String category, String accountId, String displayName) throws JSONException {
        if (accountId == null || accountId.isEmpty()) throw new JSONException("无法确认领取账号身份");
        JSONObject owners = current().getJSONObject("owners");
        String platform = platform(category);
        JSONObject previous = owners.optJSONObject(platform);
        if (previous != null && !accountId.equals(previous.getString("id")))
            throw new JSONException("当前账号与领取记录不一致，请切回原账号或开始新任务");
        owners.put(platform, new JSONObject().put("id", accountId).put("name", displayName));
    }

    private String reservationKey(JSONObject entry) throws JSONException {
        JSONObject owner = current().getJSONObject("owners").getJSONObject(platform(entry.getString("category")));
        return platform(entry.getString("category")) + ":" + owner.getString("id") + ":" + entry.getString("key");
    }

    JSONObject entry(String key) throws JSONException {
        if (current() == null) return null;
        JSONArray entries = current().getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (key.equals(entry.getString("key"))) return entry;
        }
        return null;
    }

    void plan(String category, JSONArray catalogue, long now) throws JSONException {
        JSONObject planned = current().getJSONObject("planned");
        if (!planned.optBoolean(category)) {
            JSONArray entries = current().getJSONArray("entries");
            JSONArray candidates = current().getBoolean("all") ? catalogue : current().getJSONArray("targets");
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject item = candidates.getJSONObject(i);
                if (!category.equals(item.getString("category")) || entry(item.getString("key")) != null) continue;
                entries.put(new JSONObject().put("key", item.getString("key")).put("category", category)
                        .put("title", item.getString("title")).put("status", "pending").put("reason", "等待处理"));
            }
            planned.put(category, true);
        }
        current().getJSONObject("errors").remove(category);
        JSONArray entries = current().getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (!category.equals(entry.getString("category"))) continue;
            String status = entry.getString("status");
            if (Arrays.asList("claimed", "owned", "skipped").contains(status)) continue;
            JSONObject item = find(catalogue, entry.getString("key"));
            if (item != null && item.optString("status").equals("owned") && !item.optBoolean("cached")) {
                result(entry.getString("key"), "owned", "官方库存已确认");
            } else if (reservations.has(reservationKey(entry))) {
                update(entry, "uncertain", "此前可能已提交，仅重新核实，不自动重复下单");
            } else if (item == null || !MainActivity.claimableNow(item, now)) {
                update(entry, "skipped", item == null ? "已不在当前官方活动中" : "活动未开始、已结束或已拥有");
            } else if (!Arrays.asList("unowned", "partial").contains(item.optString("status")) || item.optBoolean("cached")) {
                entry.put("pageCheck", true);
                update(entry, "pending", "先到官方商品页只读核实，尚未允许领取");
            } else {
                entry.put("pageCheck", false);
                update(entry, "pending", "等待领取");
            }
        }
    }

    private static JSONObject find(JSONArray catalogue, String key) throws JSONException {
        for (int i = 0; i < catalogue.length(); i++) {
            JSONObject item = catalogue.getJSONObject(i);
            if (key.equals(item.getString("key"))) return item;
        }
        return null;
    }

    List<JSONObject> work(String category) throws JSONException {
        List<JSONObject> result = new ArrayList<>();
        JSONArray entries = current().getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (category.equals(entry.getString("category")) && Arrays.asList("pending", "uncertain").contains(entry.getString("status"))) result.add(entry);
        }
        return result;
    }

    void working(String key) throws JSONException {
        JSONObject entry = entry(key);
        if (!entry.getString("status").equals("uncertain")) update(entry, "working", "正在核实并处理");
    }

    void reserve(String key, String stage) throws JSONException {
        if (!Arrays.asList("product", "submit").contains(stage)) throw new JSONException("领取步骤无法识别");
        JSONObject entry = entry(key);
        if (entry.optBoolean("pageCheck")) throw new JSONException("必须先完成官方商品页核实");
        if (entry.optBoolean(stage + "Authorized")) throw new JSONException("此领取步骤已处理，请先核实订单");
        String reservation = reservationKey(entry);
        String previous = reservations.optString(reservation);
        if (!previous.isEmpty() && !previous.equals(current().getString("id")))
            throw new JSONException("此项目有待核实订单，禁止再次自动提交");
        reservations.put(reservation, current().getString("id"));
        entry.put(stage + "Authorized", true);
        update(entry, "uncertain", "领取操作已准备，恢复时须先核实库存");
    }

    void prechecked(String key) throws JSONException {
        JSONObject entry = entry(key);
        if (!entry.optBoolean("pageCheck") || reservations.has(reservationKey(entry)))
            throw new JSONException("该项目不能从只读核实转为自动领取");
        entry.put("pageCheck", false);
        update(entry, "pending", "官方商品页已确认未拥有，等待领取");
    }

    void result(String key, String status, String reason) throws JSONException {
        JSONObject entry = entry(key);
        String reservation = reservationKey(entry);
        if (status.equals("owned")) {
            boolean claimed = current().getString("id").equals(reservations.optString(reservation));
            reservations.remove(reservation);
            update(entry, claimed ? "claimed" : "owned", reason);
        } else if (reservations.has(reservation)) {
            update(entry, "uncertain", "订单结果待核实；请同步或在官网查看，未自动重下单");
        } else {
            update(entry, status.equals("expired") ? "skipped" : "failed", reason);
        }
    }

    void categoryFailed(String category, String reason) throws JSONException {
        current().getJSONObject("errors").put(category, reason);
    }

    void pause(String reason) throws JSONException {
        if (current() == null) return;
        current().put("state", "paused").put("note", reason);
        JSONArray entries = current().getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entry.getString("status").equals("working")) update(entry, "pending", "处理已中断，继续前重新查库");
        }
    }

    void finish() throws JSONException {
        current().put("state", "done").put("finishedAt", System.currentTimeMillis());
    }

    private static void update(JSONObject entry, String status, String reason) throws JSONException {
        entry.put("status", status).put("reason", reason).put("updatedAt", System.currentTimeMillis());
    }

    JSONArray publicRuns() throws JSONException {
        JSONArray result = new JSONArray(runs.toString());
        for (int i = 0; i < result.length(); i++) {
            JSONObject run = result.getJSONObject(i);
            run.remove("targets");
            JSONObject owners = run.getJSONObject("owners");
            for (String platform : Arrays.asList("epic", "fab")) {
                if (owners.has(platform)) owners.getJSONObject(platform).remove("id");
            }
        }
        return result;
    }
}
