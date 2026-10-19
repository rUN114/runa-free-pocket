package cn.run.freepocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.webkit.ScriptHandler;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final Set<String> STORE_ORIGINS = new HashSet<>(Arrays.asList(
            "https://store.epicgames.com", "https://www.epicgames.com", "https://www.fab.com"));
    private final ArrayDeque<JSONObject> tasks = new ArrayDeque<>();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONArray items = new JSONArray();
    private JSONObject updated = new JSONObject();
    private JSONObject fabAccount = new JSONObject();
    private EpicSession epic;
    private ClaimJournal journal;
    private String journalError = "";
    private OfficialImages images;
    private String country = "CN";
    private String message = "正在读取本期免费内容…";
    private String completionNote = "";
    private String epicSyncError = "";
    private String manualCategory;
    private String scriptSource;
    private JSONObject active;
    private ScriptHandler script;
    private Runnable readTimeout;
    private WebView catalogue;
    private WebView store;
    private LinearLayout browser;
    private TextView browserStatus;
    private TextView browserAddress;
    private Button verifyButton;
    private boolean uiReady;
    private boolean started;
    private boolean waitingToVerify;
    private boolean resettingAccount;
    private int completed;
    private int total;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            new AlertDialog.Builder(this).setTitle("请先更新 Android System WebView")
                    .setMessage("请在手机应用商店更新 Android System WebView 或 Chrome 后重试。")
                    .setPositiveButton("关闭", (d, which) -> finish()).setCancelable(false).show();
            return;
        }
        try {
            scriptSource = readAsset("domain.js") + "\n" + readAsset("automation.js");
            loadCache();
        } catch (IOException error) { throw new IllegalStateException("应用脚本资源缺失", error); }
        initializeSession();
        try { journal = new ClaimJournal(getSharedPreferences("claim-journal", MODE_PRIVATE).getString("data", "{}")); }
        catch (JSONException error) { journalError = "领取记录无法读取，为防止重复订单已停用自动领取，请保留应用数据并检查记录。"; }
        images = new OfficialImages(new java.io.File(getCacheDir(), "official-images"));
        buildViews();
        catalogue.loadUrl(APP_ORIGIN + "/assets/index.html");
    }

    private void initializeSession() {
        try { epic = new EpicSession(this); }
        catch (GeneralSecurityException | JSONException | IOException | IllegalArgumentException error) {
            // A broken derived session can be replaced by a fresh official authorization, never plaintext storage.
            getSharedPreferences("epic-auth", MODE_PRIVATE).edit().remove("encrypted").apply();
            message = "本机授权记录无法读取，请重新连接 Epic 账号。";
            try { epic = new EpicSession(this); }
            catch (GeneralSecurityException | JSONException | IOException unavailable) {
                throw new IllegalStateException("无法初始化授权存储", unavailable);
            }
        }
    }

    private String readAsset(String name) throws IOException {
        try (var stream = getAssets().open(name); var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void loadCache() {
        var preferences = getSharedPreferences("catalogue", MODE_PRIVATE);
        country = preferences.getString("country", "CN");
        try {
            fabAccount = new JSONObject(preferences.getString("fabAccount", "{}"));
            // Re-read derived metadata after promotion/ownership rules change; retain official sign-in.
            if (preferences.getInt("schema", 1) != 3) return;
            items = new JSONArray(preferences.getString("items", "[]"));
            updated = new JSONObject(preferences.getString("updated", "{}"));
            for (int i = 0; i < items.length(); i++) items.getJSONObject(i).put("status", "unknown");
        } catch (JSONException error) {
            items = new JSONArray();
            updated = new JSONObject();
            message = "活动缓存无法读取，请重新刷新。";
        }
    }

    private void buildViews() {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 19, 41));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var system = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(system.left, system.top, system.right, system.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        catalogue = new WebView(this);
        configure(catalogue);
        catalogue.setBackgroundColor(Color.rgb(7, 19, 41));
        var assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        catalogue.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_ORIGIN.equals(uri.getScheme() + "://" + uri.getHost()) && uri.getPath() != null && uri.getPath().startsWith("/images/")) {
                    try {
                        byte[] bytes = images.read(uri.getLastPathSegment(), "1".equals(uri.getQueryParameter("cover")));
                        return new WebResourceResponse(OfficialImages.mime(bytes), null, new ByteArrayInputStream(bytes));
                    } catch (IOException error) {
                        android.util.Log.e("PocketImages", "Artwork load failed: " + error.getMessage(), error);
                        return new WebResourceResponse("text/plain", "UTF-8", 502, "Image unavailable", Collections.emptyMap(),
                                new ByteArrayInputStream("图片读取失败，请重试或检查网络".getBytes(StandardCharsets.UTF_8)));
                    }
                }
                return assets.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().equals(APP_ORIGIN + "/assets/index.html");
            }
            @Override public void onPageFinished(WebView view, String url) {
                uiReady = true;
                publish();
                if (started) return;
                started = true;
                try {
                    if (getSharedPreferences("catalogue", MODE_PRIVATE).getBoolean("accountResetPending", false)) {
                        resetAccount(false, "pc");
                        return;
                    }
                    tasks.add(task("discover", "pc"));
                    if (epic.connected()) tasks.add(task("syncEpic", "pc"));
                    beginQueue();
                } catch (JSONException error) { fail("无法启动活动读取，请点击刷新重试。"); }
            }
        });
        WebViewCompat.addWebMessageListener(catalogue, "PocketApp", Collections.singleton(APP_ORIGIN),
                (view, data, origin, main, reply) -> {
                    if (!main || !origin.toString().equals(APP_ORIGIN)) return;
                    try { handleCommand(new JSONObject(data.getData())); }
                    catch (JSONException error) { fail("应用消息格式无效，请重新打开程序。"); }
                });
        root.addView(catalogue, new FrameLayout.LayoutParams(-1, -1));
        browser = new LinearLayout(this);
        browser.setOrientation(LinearLayout.VERTICAL);
        browser.setBackgroundColor(Color.WHITE);
        browserStatus = new TextView(this);
        browserStatus.setTextColor(Color.rgb(24, 35, 64));
        browserStatus.setTextSize(14);
        browserStatus.setPadding(dp(14), dp(10), dp(14), dp(4));
        browser.addView(browserStatus);
        browserAddress = new TextView(this);
        browserAddress.setTextSize(11);
        browserAddress.setTextColor(Color.rgb(89, 102, 126));
        browserAddress.setPadding(dp(14), 0, dp(14), dp(5));
        browser.addView(browserAddress);
        LinearLayout controls = new LinearLayout(this);
        addButton(controls, "返回列表", this::stop);
        verifyButton = addButton(controls, "核实结果", this::verifyCurrent);
        addButton(controls, "跳过此项", this::skip);
        browser.addView(controls);
        createStoreView();
        root.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        browser.setVisibility(View.GONE);
        setContentView(root);
    }

    private void createStoreView() {
        store = new WebView(this);
        configure(store);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(store, true);
        store.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"https".equals(request.getUrl().getScheme());
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                Uri uri = Uri.parse(url);
                browserAddress.setText(uri.getHost());
                boolean login = "www.epicgames.com".equals(uri.getHost()) && uri.getPath() != null
                        && uri.getPath().startsWith("/id/login");
                if (!login || !epic.connected() || (active != null && active.optString("mode").equals("auth"))) return;
                if ((active != null && active.optString("category").equals("fab")) || "fab".equals(manualCategory)) return;
                epic.disconnect();
                try { invalidateOwnership(items, "epic"); publish(); }
                catch (JSONException error) { fail("拥有状态无法重置，请重新刷新。"); }
                if (active != null) fail("Epic 网页登录已过期，请重新连接账号后继续领取。");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) browserStatus.setText("网页加载失败，请检查网络，返回列表后重试。");
            }
        });
        WebViewCompat.addWebMessageListener(store, "PocketStore", STORE_ORIGINS,
                (view, data, origin, main, reply) -> {
                    if (active == null || !STORE_ORIGINS.contains(origin.toString())) return;
                    try { handleStoreMessage(new JSONObject(data.getData()), origin.toString(), main, reply); }
                    catch (JSONException error) { fail("商店返回格式无法识别，请重新刷新。"); }
                });
        browser.addView(store, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void configure(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Button addButton(LinearLayout row, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        return button;
    }

    private JSONObject task(String mode, String category) throws JSONException {
        return new JSONObject().put("mode", mode).put("category", category);
    }

    private boolean validCategory(String category) { return Arrays.asList("pc", "android", "fab").contains(category); }

    private void handleCommand(JSONObject command) throws JSONException {
        if (active != null || resettingAccount) return;
        String action = command.getString("action");
        if (action.equals("claimAll") || action.equals("resumeBatch")) {
            if (journal == null || !journalError.isEmpty()) { message = journalError; publish(); return; }
            if (action.equals("claimAll")) journal.start(country, Arrays.asList("pc", "android", "fab"), null);
            else journal.resume(country);
            if (!saveJournal()) return;
            queueBatch();
            return;
        }
        if (action.equals("country")) {
            String choice = command.getString("country");
            if (!Arrays.asList("CN", "HK", "TW", "US", "JP", "GB", "DE").contains(choice)) return;
            country = choice;
            items = new JSONArray();
            updated = new JSONObject();
            tasks.add(task("discover", "pc"));
            if (epic.connected()) tasks.add(task("syncEpic", "pc"));
            save(); beginQueue(); return;
        }
        if (action.equals("open")) {
            JSONObject item = find(command.getString("key"));
            if (item == null) return;
            manualCategory = item.getString("category");
            verifyButton.setEnabled(true);
            verifyButton.setText("同步结果");
            browserStatus.setText("官方商品页。返回列表后会自动同步当前分类的拥有状态。");
            openBrowser(item.getString("url"));
            return;
        }
        String category = command.getString("category");
        if (!validCategory(category)) return;
        if (action.equals("switchAccount") || action.equals("logout")) {
            resetAccount(action.equals("switchAccount"), category);
            return;
        }
        if (action.equals("login")) {
            if (category.equals("fab")) { fabAccount = new JSONObject(); invalidateOwnership(items, "fab"); }
            else { epic.disconnect(); epicSyncError = ""; invalidateOwnership(items, "epic"); }
            tasks.add(task("auth", category));
            tasks.add(task("discover", category));
            tasks.add(task(category.equals("fab") ? "syncFab" : "syncEpic", category));
        } else if (action.equals("refresh")) {
            tasks.add(task("discover", category));
            if (category.equals("fab")) {
                if (fabAccount.optBoolean("connected")) tasks.add(task("syncFab", category));
            } else if (epic.connected()) tasks.add(task("syncEpic", category));
        } else if (action.equals("check")) {
            if (category.equals("fab") && !fabAccount.optBoolean("connected")) tasks.add(task("auth", category));
            if (!category.equals("fab") && !epic.connected()) tasks.add(task("auth", category));
            tasks.add(task(category.equals("fab") ? "syncFab" : "syncEpic", category));
        } else if (action.equals("claim")) {
            if (journal == null || !journalError.isEmpty()) { message = journalError; publish(); return; }
            JSONArray keys = command.getJSONArray("keys");
            JSONArray selected = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < keys.length(); i++) {
                JSONObject item = find(keys.getString(i));
                if (item == null || !category.equals(item.getString("category")) || !seen.add(item.getString("key"))) continue;
                selected.put(new JSONObject().put("key", item.getString("key")).put("title", item.getString("title")).put("category", category));
            }
            if (selected.length() == 0) return;
            journal.start(country, Arrays.asList(category), selected);
            if (!saveJournal()) return;
            queueBatch();
            return;
        }
        beginQueue();
    }

    private void queueBatch() throws JSONException {
        for (String category : journal.categories()) {
            tasks.add(task("discover", category));
            tasks.add(task("ensureAuth", category));
            tasks.add(task(category.equals("fab") ? "syncFab" : "syncEpic", category).put("forClaim", true));
            tasks.add(task("planBatch", category));
        }
        beginQueue();
    }

    private boolean saveJournal() {
        // Do not acknowledge a page action until its recovery checkpoint is on disk.
        if (getSharedPreferences("claim-journal", MODE_PRIVATE).edit().putString("data", journal.serialize()).commit()) return true;
        cancelReadTimeout();
        removeScript();
        tasks.clear();
        active = null;
        store.stopLoading();
        store.loadUrl("about:blank");
        browser.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        message = "无法保存领取进度，已停止自动操作。请检查手机存储空间后重试。";
        publish();
        return false;
    }

    private boolean bindBatchAccount(String category) {
        if (journal == null || !journal.running()) return true;
        try {
            journal.bind(category, category.equals("fab") ? fabAccount.getString("accountId") : epic.accountId(),
                    category.equals("fab") ? fabAccount.getString("displayName") : epic.displayName());
            return true;
        } catch (JSONException error) {
            fail("当前账号未连接或与原领取记录不一致，请重新连接原账号，或开始新任务。");
            return false;
        }
    }

    private void beginQueue() {
        completed = 0;
        completionNote = "";
        total = tasks.size();
        if (total > 0) next();
    }

    private JSONObject find(String key) throws JSONException {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.getString("key").equals(key)) return item;
        }
        return null;
    }

    private void next() {
        cancelReadTimeout();
        removeScript();
        store.stopLoading();
        active = tasks.poll();
        try {
            while (active != null) {
                String mode = active.getString("mode");
                String category = active.getString("category");
                if (mode.equals("ensureAuth")) {
                    if (category.equals("fab") ? fabAccount.optBoolean("connected") : epic.connected()) {
                        completed++; active = tasks.poll(); continue;
                    }
                    active.put("mode", "auth");
                    break;
                }
                if (mode.equals("planBatch")) {
                    if (!bindBatchAccount(category)) return;
                    journal.plan(category, items, System.currentTimeMillis());
                    List<JSONObject> work = journal.work(category);
                    for (int i = work.size() - 1; i >= 0; i--) {
                        JSONObject entry = work.get(i);
                        JSONObject item = find(entry.getString("key"));
                        if (item != null) {
                            String entryMode = "claim";
                            if (entry.getString("status").equals("uncertain")) entryMode = category.equals("fab") ? "check" : "verifyEpic";
                            else if (entry.optBoolean("pageCheck")) entryMode = "check";
                            tasks.addFirst(task(entryMode, category).put("item", item)
                                    .put("precheck", entry.getString("status").equals("pending") && entry.optBoolean("pageCheck")));
                        }
                    }
                    total += work.size();
                    if (!saveJournal()) return;
                    completed++; active = tasks.poll(); continue;
                }
                if (!mode.equals("claim") && !mode.equals("check") && !mode.equals("verifyEpic")) break;
                if (!active.has("item")) break;
                JSONObject current = find(active.getJSONObject("item").getString("key"));
                // Login expiry can invalidate an item after the batch was planned.
                if (mode.equals("claim") && current != null &&
                        (!Arrays.asList("unowned", "partial").contains(current.optString("status")) || current.optBoolean("cached"))) {
                    if (journal != null && journal.running()) {
                        String resultStatus = current.optString("status");
                        if (current.optBoolean("cached")) resultStatus = "error";
                        journal.result(current.getString("key"), resultStatus, "拥有状态已变化，请先同步库存再重试");
                        if (!saveJournal()) return;
                    }
                    completed++; active = tasks.poll(); continue;
                }
                if (current != null && (!mode.equals("claim") || claimableNow(current, System.currentTimeMillis()))) {
                    active.put("item", current);
                    if (journal != null && journal.running()) {
                        journal.working(current.getString("key"));
                        if (!saveJournal()) return;
                    }
                    break;
                }
                if (journal != null && journal.running()) {
                    journal.result(active.getJSONObject("item").getString("key"), current == null ? "expired" : current.optString("status"), "当前活动或拥有状态已变化");
                    if (!saveJournal()) return;
                }
                completed++;
                active = tasks.poll();
            }
        } catch (JSONException error) { fail("领取队列无法读取，请重新选择。"); return; }
        waitingToVerify = false;
        if (active == null) {
            try {
                if (journal != null && journal.running()) {
                    journal.finish();
                    if (!saveJournal()) return;
                    completionNote = "本次领取处理已结束，请查看领取记录；待核实项目不会重复自动下单。";
                }
            } catch (JSONException error) { message = "领取结果记录异常，请同步库存核实。"; publish(); return; }
            message = completionNote.isEmpty() ? "活动已更新。连接账号后，拥有状态会自动同步。" : completionNote;
            browser.setVisibility(View.GONE);
            store.loadUrl("about:blank");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            save(); publish(); return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startActive();
    }

    private void startActive() {
        try {
            active.put("nonce", UUID.randomUUID().toString()).put("country", country);
            String nonce = active.getString("nonce");
            String category = active.getString("category");
            String mode = active.getString("mode");
            if (active.has("item") && journal != null && journal.running()) {
                if (!bindBatchAccount(category)) return;
                if (category.equals("fab")) active.put("accountId", fabAccount.getString("accountId"));
            }
            verifyButton.setEnabled(true);
            verifyButton.setText(mode.equals("auth") ? "重试连接" : "重试读取");
            if (mode.equals("syncEpic") || mode.equals("verifyEpic")) {
                browser.setVisibility(View.GONE);
                store.loadUrl("about:blank");
                message = "正在同步 Epic 账号的完整拥有凭证…";
                publish(); requestEpicOwnership(nonce); return;
            }
            if (category.equals("pc") && mode.equals("discover")) {
                browser.setVisibility(View.GONE);
                store.loadUrl("about:blank");
                message = "正在读取 Epic 电脑游戏官方活动…";
                publish(); requestPcCatalogue(nonce); return;
            }
            script = WebViewCompat.addDocumentStartJavaScript(store,
                    scriptSource + "\nrunPocketTask(" + active + ");", STORE_ORIGINS);
            String url = category.equals("fab") ? "https://www.fab.com/limited-time-free?lang=en" : "https://store.epicgames.com/en-US/free-games";
            String title = "读取活动";
            if (mode.equals("auth")) {
                url = category.equals("fab") ? "https://www.fab.com/login" : EpicSession.authorizationUrl();
                title = "完成官方登录后，将自动返回并同步拥有状态";
            } else if (mode.equals("syncFab")) title = "同步 Fab 素材库";
            if (active.has("item")) {
                verifyButton.setText("核实结果");
                url = active.getJSONObject("item").getString("url");
                title = active.getJSONObject("item").getString("title");
            }
            browserStatus.setText((completed + 1) + "/" + total + "  " + title + "\n验证码和许可协议请在下方官方页面处理。");
            message = "正在处理：" + title;
            openBrowser(url);
            if (mode.equals("discover") || mode.equals("syncFab")) scheduleReadTimeout(nonce);
            publish();
        } catch (JSONException error) { fail("任务数据无效，请重新刷新。"); }
    }

    private interface ItemsCallback { void accept(JSONArray value) throws JSONException; }

    private void evaluateItems(String expression, String nonce, ItemsCallback callback) {
        String code = "(()=>{try{return JSON.stringify({items:" + expression
                + "});}catch(e){return JSON.stringify({error:e.message});}})()";
        catalogue.evaluateJavascript(code, raw -> {
            if (!current(nonce)) return;
            try {
                Object decoded = new JSONTokener(raw).nextValue();
                if (!(decoded instanceof String)) throw new JSONException("解析器没有返回结果");
                JSONObject result = new JSONObject((String) decoded);
                if (result.has("error")) throw new JSONException(result.getString("error"));
                callback.accept(result.getJSONArray("items"));
            } catch (JSONException error) { fail("数据解析失败：" + error.getMessage()); }
        });
    }

    private boolean current(String nonce) { return active != null && nonce.equals(active.optString("nonce")); }

    static boolean claimableNow(JSONObject item, long now) {
        if (Arrays.asList("owned", "expired").contains(item.optString("status"))) return false;
        if (item.optString("availability").equals("expired")) return false;
        if (item.optString("availability").equals("upcoming") && item.optString("start").isEmpty()) return false;
        try {
            Object start = item.opt("start");
            if (start != null && start != JSONObject.NULL && !start.toString().isEmpty()) {
                long time = start instanceof Number ? ((Number) start).longValue() : java.time.Instant.parse(start.toString()).toEpochMilli();
                if (time > now) return false;
            }
            String end = item.optString("end");
            return end.isEmpty() || java.time.Instant.parse(end).toEpochMilli() > now;
        } catch (java.time.format.DateTimeParseException error) { return false; }
    }

    private void requestPcCatalogue(String nonce) {
        String requestedCountry = country;
        network.execute(() -> {
            try {
                String json = EpicPublicApi.promotions(requestedCountry);
                runOnUiThread(() -> {
                    if (!current(nonce)) return;
                    evaluateItems("PocketDomain.parsePc(JSON.parse(" + JSONObject.quote(json) + "))", nonce, fresh -> {
                        replaceCategory("pc", fresh);
                        updated.put("pc", System.currentTimeMillis());
                        finishCatalogue(nonce);
                    });
                });
            } catch (IOException error) {
                runOnUiThread(() -> { if (current(nonce)) fail("电脑活动读取失败，请检查网络后重试。"); });
            }
        });
    }

    private void finishCatalogue(String nonce) {
        JSONObject cached = epic.cachedSnapshot();
        String category = active.optString("category");
        if (cached == null || category.equals("fab")) { completed++; next(); return; }
        evaluateItems("PocketDomain.applyOwnership(" + items + "," + cached + ",false," + JSONObject.quote(category) + ")", nonce, value -> {
            items = value;
            completed++; next();
        });
    }

    private void acceptAuthorization(String code, String nonce) {
        network.execute(() -> {
            try {
                JSONObject session = EpicSession.exchange(code);
                runOnUiThread(() -> {
                    if (!current(nonce)) return;
                    try { epic.save(session); epicSyncError = ""; completed++; next(); }
                    catch (GeneralSecurityException | IOException error) { fail("无法安全保存授权，请重新连接账号。"); }
                });
            } catch (IOException error) {
                runOnUiThread(() -> { if (current(nonce)) fail("账号授权请求失败：" + error.getMessage()); });
            } catch (JSONException error) {
                runOnUiThread(() -> { if (current(nonce)) fail("未取得有效的库存授权，请重新连接 Epic 账号。"); });
            }
        });
    }

    private void requestEpicOwnership(String nonce) {
        String category = active.optString("category");
        JSONArray requested = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && category.equals(item.optString("category"))) requested.put(item);
        }
        String requestedJson = requested.toString();
        network.execute(() -> {
            try {
                EpicSession.Ownership result = epic.readOwnership(new JSONArray(requestedJson));
                runOnUiThread(() -> {
                    if (!current(nonce)) return;
                    try {
                        epic.save(result.session);
                        epicSyncError = "";
                        evaluateItems("PocketDomain.applyOwnership(" + items + "," + result.snapshot + ",true," + JSONObject.quote(category) + ")", nonce, value -> {
                            items = value;
                            if (active.optBoolean("forClaim") && !bindBatchAccount(category)) return;
                            completionNote = "拥有状态已同步：" + epic.displayName() + "。已拥有的项目会跳过。";
                            if (active.has("item")) {
                                JSONObject checked = find(active.getJSONObject("item").getString("key"));
                                if (!active.optBoolean("precheck") && checked != null && !checked.optString("status").equals("owned")) {
                                    if (active.optInt("verifyAttempt", 0) == 0) {
                                        active.put("verifyAttempt", 1);
                                        message = "订单已提交，等待拥有凭证更新…";
                                        readTimeout = () -> { if (current(nonce)) requestEpicOwnership(nonce); };
                                        handler.postDelayed(readTimeout, 3000);
                                        publish(); return;
                                    }
                                    checked.put("status", "error").put("statusReason", "订单尚未被账号拥有凭证确认");
                                    completionNote = "有订单尚未确认，请查看卡片状态后重试同步。";
                                }
                            }
                            if (active.has("item")) {
                                JSONObject checked = find(active.getJSONObject("item").getString("key"));
                                if (!recordBatchItem(checked, checked.optString("status"), checked.optString("statusReason"))) return;
                            }
                            completed++; next();
                        });
                    } catch (GeneralSecurityException | IOException error) { fail("无法安全保存账号会话，请重新连接 Epic。"); }
                });
            } catch (IOException | JSONException error) {
                runOnUiThread(() -> { if (current(nonce)) syncFailed(error.getMessage()); });
            }
        });
    }

    private void handleStoreMessage(JSONObject data, String origin, boolean main, JavaScriptReplyProxy reply) throws JSONException {
        if (!current(data.optString("nonce"))) return;
        String type = data.getString("type");
        String category = active.getString("category");
        String mode = active.getString("mode");
        String productOrigin = category.equals("fab") ? "https://www.fab.com" : "https://store.epicgames.com";
        if (type.equals("actionReady")) {
            if (!mode.equals("claim") || !active.has("item") || journal == null || !journal.running()) return;
            String stage = data.getString("stage");
            if (stage.equals("product") && (!main || !origin.equals(productOrigin))) return;
            if (!bindBatchAccount(category)) return;
            try { journal.reserve(active.getJSONObject("item").getString("key"), stage); }
            catch (JSONException error) { fail("此项目存在待核实操作或重复请求，请先在领取记录中核实。"); return; }
            if (!saveJournal()) return;
            reply.postMessage(new JSONObject().put("type", "actionPermit").put("nonce", active.getString("nonce")).put("stage", stage).toString());
            return;
        }
        if (Arrays.asList("catalogue", "result", "error", "ownership").contains(type)
                && (!main || !origin.equals(productOrigin))) return;
        if (type.equals("authorized")) {
            String code = data.getString("code");
            if (!main || !origin.equals("https://www.epicgames.com") || !mode.equals("auth")
                    || category.equals("fab") || !code.matches("[a-zA-Z0-9_-]{16,1024}")) return;
            active.put("mode", "authorizing");
            verifyButton.setEnabled(false);
            browserStatus.setText("正在完成账号授权并读取库存…");
            acceptAuthorization(code, active.getString("nonce"));
        } else if (type.equals("authenticated")) {
            if (!main || !origin.equals("https://www.fab.com") || !category.equals("fab") || !mode.equals("auth")) return;
            fabAccount.put("connected", true).put("displayName", data.getString("displayName"));
            completed++; next();
        } else if (type.equals("ownership")) {
            if (!category.equals("fab") || !mode.equals("syncFab")) return;
            acceptFabOwnership(data);
            if (active.optBoolean("forClaim") && !bindBatchAccount(category)) return;
            completed++; next();
        } else if (type.equals("catalogue")) {
            if (!mode.equals("discover")) return;
            replaceCategory(category, data.getJSONArray("items"));
            updated.put(category, System.currentTimeMillis());
            finishCatalogue(active.getString("nonce"));
        } else if (type.equals("result")) {
            if (!Arrays.asList("claim", "check").contains(mode) || !active.has("item")) return;
            String status = data.getString("status");
            if (!Arrays.asList("owned", "unowned", "expired").contains(status)) throw new JSONException("未知拥有状态");
            JSONObject item = find(active.getJSONObject("item").getString("key"));
            if (item != null) item.put("status", status).put("checkedAt", System.currentTimeMillis()).put("cached", false)
                    .put("statusReason", category.equals("fab") ? "Fab 素材库已核实" : "官方商品页面已核实");
            if (!recordBatchItem(item, status, data.optString("message", "官方页面及库存已核实"))) return;
            completed++; next();
        } else if (type.equals("checkoutComplete")) {
            if (mode.equals("claim")) verifyCurrent();
        } else if (type.equals("ordered")) {
            if (mode.equals("claim") && !waitingToVerify) {
                waitingToVerify = true;
                browserStatus.setText("已提交零元订单，等待官方结果。若有验证码请完成，完成后可点“核实结果”。");
            }
        } else if (type.equals("waiting")) {
            browserStatus.setText(data.getString("message") + "\n当前 " + (completed + 1) + "/" + total);
        } else if (type.equals("blocked")) {
            if (mode.equals("claim")) fail(data.getString("message"));
        } else if (type.equals("error")) {
            if (mode.equals("syncFab") || mode.equals("discover")) {
                cancelReadTimeout();
                if (mode.equals("syncFab")) markSyncError(data.getString("message"));
                else completionNote = "活动读取失败：" + data.getString("message");
                browserStatus.setText(data.getString("message") + "。请完成网页登录或验证，再点“重试读取”。也可以返回列表稍后重试。");
                publish(); return;
            }
            browserStatus.setText(data.getString("message") + "。处理网页后点“核实结果”，或跳过此项。");
            if (active.has("item")) active.getJSONObject("item").put("status", "error").put("statusReason", data.getString("message"));
            publish();
        }
    }

    private boolean recordBatchItem(JSONObject item, String status, String reason) throws JSONException {
        if (journal == null || !journal.running()) return true;
        String key = active.getJSONObject("item").getString("key");
        if (active.optBoolean("precheck") && status.equals("unowned") && item != null && claimableNow(item, System.currentTimeMillis())) {
            journal.prechecked(key);
            tasks.addFirst(task("claim", active.getString("category")).put("item", item));
            total++;
        } else journal.result(key, status, reason);
        return saveJournal();
    }

    private void acceptFabOwnership(JSONObject data) throws JSONException {
        JSONArray acquired = data.getJSONArray("owned");
        Set<String> uids = new HashSet<>();
        for (int i = 0; i < acquired.length(); i++) uids.add(acquired.getString(i));
        long time = data.getLong("checkedAt");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.getString("category").equals("fab")) item.put("status", uids.contains(item.getString("id")) ? "owned" : "unowned")
                    .put("checkedAt", time).put("cached", false).put("statusReason", "Fab 已获取素材库核实");
        }
        fabAccount = new JSONObject().put("connected", true).put("displayName", data.getString("displayName"))
                .put("accountId", data.getString("accountId")).put("checkedAt", time);
        completionNote = "Fab 拥有状态已同步，已拥有的素材会跳过。";
    }

    private void syncFailed(String reason) {
        try {
            markSyncError(reason);
            if (active.optBoolean("forClaim") || active.optString("mode").equals("verifyEpic")) { fail(completionNote); return; }
            completed++; next();
        } catch (JSONException error) { fail("同步结果无法更新，请重新刷新。"); }
    }

    private void markSyncError(String reason) throws JSONException {
        boolean fab = active.optString("category").equals("fab");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            boolean fabItem = item.getString("category").equals("fab");
            if (fabItem != fab || !item.getString("category").equals(active.optString("category"))) continue;
            item.put("statusReason", reason).put("cached", true);
            if (!item.optString("status").equals("owned")) item.put("status", "error");
        }
        completionNote = "拥有状态同步失败：" + reason;
        if (fab) fabAccount.put("error", reason); else epicSyncError = reason;
    }

    private void replaceCategory(String category, JSONArray fresh) throws JSONException {
        JSONArray merged = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (!item.getString("category").equals(category)) merged.put(item);
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < fresh.length(); i++) {
            JSONObject item = fresh.getJSONObject(i);
            Uri uri = Uri.parse(item.getString("url"));
            String expected = category.equals("fab") ? "www.fab.com" : "store.epicgames.com";
            if (!category.equals(item.getString("category")) || !"https".equals(uri.getScheme())
                    || !expected.equals(uri.getHost())) throw new JSONException("商品地址不属于官方商城");
            if (keys.add(item.getString("key"))) merged.put(item.put("status", "unknown"));
        }
        items = merged;
        save();
    }

    private void scheduleReadTimeout(String nonce) {
        readTimeout = () -> {
            if (!current(nonce)) return;
            browserStatus.setText("网页读取超时。请检查网络，完成官网验证后点“重试读取”；也可以返回列表稍后重试。");
        };
        handler.postDelayed(readTimeout, 40000);
    }

    private void cancelReadTimeout() {
        if (readTimeout != null) { handler.removeCallbacks(readTimeout); readTimeout = null; }
    }

    private void verifyCurrent() {
        if (active == null || !active.has("item")) {
            if (active == null) { stop(); return; }
            cancelReadTimeout(); removeScript(); startActive();
            return;
        }
        try {
            removeScript();
            JSONArray catalogItems = active.getJSONObject("item").optJSONArray("catalogItems");
            boolean pageOnly = active.getString("category").equals("fab") || catalogItems == null || catalogItems.length() == 0;
            active.put("mode", pageOnly ? "check" : "verifyEpic");
            waitingToVerify = false;
            startActive();
        } catch (JSONException error) { fail("无法核实当前商品，请重新同步。"); }
    }

    private void skip() {
        if (active == null) { stop(); return; }
        if (!active.optString("mode").equals("claim") && !active.optString("mode").equals("check")) { stop(); return; }
        try {
            if (journal != null && journal.running() && active.has("item")) {
                journal.result(active.getJSONObject("item").getString("key"), "error", "用户跳过，可在记录中重试未提交项目");
                if (!saveJournal()) return;
            }
        } catch (JSONException error) { fail("无法记录跳过结果，请同步库存。"); return; }
        completed++;
        next();
    }

    private void fail(String reason) {
        manualCategory = null;
        if (journal != null && journal.running() && active != null) {
            try {
                if (active.has("item")) journal.result(active.getJSONObject("item").getString("key"), "error", reason);
                else {
                    String category = active.getString("category");
                    journal.categoryFailed(category, reason);
                    tasks.removeIf(task -> category.equals(task.optString("category")));
                }
                if (!saveJournal()) return;
                completionNote = reason;
                completed++; next(); return;
            } catch (JSONException error) { message = "领取记录无法更新，已停止。请同步库存核实结果。"; }
        }
        stop();
        message = reason;
        publish();
    }

    private void stop() {
        String returnCategory = active == null ? manualCategory : null;
        manualCategory = null;
        if (journal != null && journal.running()) {
            try { journal.pause("已暂停；继续前会重新查库，待核实订单不会自动重下单"); }
            catch (JSONException error) { journalError = "领取记录无法暂停，请同步库存核实。"; }
            if (!saveJournal()) return;
        }
        if (active != null) message = completionNote.isEmpty() ? "本次处理已停止。未确认的订单可点击同步重新核实。" : completionNote;
        cancelReadTimeout();
        removeScript();
        tasks.clear();
        active = null;
        store.stopLoading();
        store.loadUrl("about:blank");
        browser.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CookieManager.getInstance().flush();
        save(); publish();
        if (returnCategory == null) return;
        try {
            if (returnCategory.equals("fab")) tasks.add(task("syncFab", "fab"));
            else {
                if (!epic.connected()) tasks.add(task("auth", returnCategory));
                tasks.add(task("syncEpic", returnCategory));
            }
            beginQueue();
        } catch (JSONException error) { message = "自动同步未启动，请点击同步按钮。"; publish(); }
    }

    private void removeScript() { if (script != null) { script.remove(); script = null; } }
    private void openBrowser(String url) { browser.setVisibility(View.VISIBLE); store.loadUrl(url); }

    private void resetAccount(boolean reconnect, String category) throws JSONException {
        // Epic and Fab share this app's browser cookies. Switching must clear both identities.
        invalidateOwnership(items, "all");
        if (journal != null) {
            if (journal.running()) journal.pause("账号已退出，继续任务前必须登录原账号");
            if (!getSharedPreferences("claim-journal", MODE_PRIVATE).edit().putString("data", journal.serialize()).commit())
                journalError = "无法保存领取记录，已停用自动领取；退出登录仍会继续。请释放存储空间后重新打开。";
        }
        resettingAccount = true;
        epic.disconnect();
        fabAccount = new JSONObject();
        epicSyncError = "";
        completionNote = "";
        manualCategory = null;
        message = "正在退出旧账号并清理登录状态…";
        cancelReadTimeout();
        removeScript();
        tasks.clear();
        store.stopLoading();
        browser.removeView(store);
        store.clearHistory();
        store.clearCache(true);
        store.destroy();
        store = null;
        browser.setVisibility(View.GONE);
        WebStorage.getInstance().deleteAllData();
        // Persist the pending reset so an interrupted cleanup completes before any future login.
        save();
        publish();
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(removed -> {
            cookies.flush();
            if (isDestroyed()) return;
            createStoreView();
            resettingAccount = false;
            message = "已退出 Epic 和 Fab。登录后会重新核实当前账号的拥有状态。";
            save();
            publish();
            if (!reconnect) return;
            try {
                tasks.add(task("auth", category));
                tasks.add(task("discover", category));
                tasks.add(task(category.equals("fab") ? "syncFab" : "syncEpic", category));
                beginQueue();
            } catch (JSONException error) { fail("已退出旧账号，请重新点击连接账号。"); }
        });
    }

    static void invalidateOwnership(JSONArray items, String category) throws JSONException {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String itemCategory = item.getString("category");
            boolean matches = category.equals("all") || category.equals(itemCategory)
                    || (category.equals("epic") && !itemCategory.equals("fab"));
            if (!matches) continue;
            item.put("status", "unknown");
            item.remove("checkedAt");
            item.remove("cached");
            item.remove("statusReason");
        }
    }

    private void save() {
        getSharedPreferences("catalogue", MODE_PRIVATE).edit().putInt("schema", 3).putString("items", items.toString())
                .putString("updated", updated.toString()).putString("country", country).putString("fabAccount", fabAccount.toString())
                .putBoolean("accountResetPending", resettingAccount).apply();
    }

    private void publish() {
        if (!uiReady) return;
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                item.put("localCover", images.register(item.optString("cover"), true));
                JSONArray local = new JSONArray(), screenshots = item.optJSONArray("screenshots");
                if (screenshots != null) for (int j = 0; j < screenshots.length(); j++) local.put(images.register(screenshots.optString(j), false));
                item.put("localScreenshots", local);
            }
            JSONObject account = new JSONObject().put("connected", epic.connected()).put("displayName", epic.displayName())
                    .put("checkedAt", epic.cachedSnapshot() == null ? 0 : epic.cachedSnapshot().optLong("checkedAt")).put("error", epicSyncError);
            JSONObject fabStatus = new JSONObject(fabAccount.toString());
            fabStatus.remove("accountId");
            JSONObject state = new JSONObject().put("items", items).put("updated", updated).put("country", country)
                    .put("message", message).put("busy", active != null || resettingAccount).put("fabAccount", fabStatus).put("epicAccount", account)
                    .put("history", journal == null ? new JSONArray() : journal.publicRuns()).put("journalError", journalError);
            catalogue.evaluateJavascript("window.receiveState(" + state + ");", null);
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    @Override public void onBackPressed() {
        if (browser != null && browser.getVisibility() == View.VISIBLE) stop();
        else if (uiReady) catalogue.evaluateJavascript("window.closeDetail && window.closeDetail()", closed -> { if (!"true".equals(closed)) finish(); });
        else super.onBackPressed();
    }

    @Override public void onResume() {
        super.onResume();
        if (catalogue != null && uiReady && active == null && manualCategory == null && !resettingAccount)
            catalogue.evaluateJavascript("window.refreshIfDue && window.refreshIfDue()", null);
    }

    @Override public void onDestroy() {
        if (journal != null && journal.running()) {
            try {
                journal.pause("应用已关闭，继续前将重新核实库存");
                getSharedPreferences("claim-journal", MODE_PRIVATE).edit().putString("data", journal.serialize()).commit();
            } catch (JSONException error) { android.util.Log.e("ClaimJournal", "Unable to checkpoint closing batch", error); }
        }
        active = null;
        tasks.clear();
        cancelReadTimeout();
        network.shutdownNow();
        removeScript();
        if (store != null) { store.stopLoading(); store.destroy(); }
        if (catalogue != null) catalogue.destroy();
        super.onDestroy();
    }
}
