package cn.run.freepocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
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
    private JSONArray items = new JSONArray();
    private JSONObject updated = new JSONObject();
    private String country = "CN";
    private String message = "先确认账号地区，在官方网页登录，然后刷新活动。";
    private String scriptSource;
    private JSONObject active;
    private ScriptHandler script;
    private WebView catalogue;
    private WebView store;
    private LinearLayout browser;
    private TextView browserStatus;
    private TextView browserAddress;
    private boolean uiReady;
    private boolean waitingToVerify;
    private int completed;
    private int total;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            new AlertDialog.Builder(this).setTitle("请先更新 Android System WebView")
                    .setMessage("本程序需要支持安全网页消息和页面脚本的 WebView。请在手机应用商店更新 Android System WebView 或 Chrome 后重试。")
                    .setPositiveButton("关闭", (d, which) -> finish()).setCancelable(false).show();
            return;
        }
        try {
            scriptSource = readAsset("domain.js") + "\n" + readAsset("automation.js");
            loadCache();
        } catch (IOException error) {
            throw new IllegalStateException("应用脚本资源缺失", error);
        }
        buildViews();
        catalogue.loadUrl(APP_ORIGIN + "/assets/index.html");
    }

    private String readAsset(String name) throws IOException {
        try (var stream = getAssets().open(name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
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
            items = new JSONArray(preferences.getString("items", "[]"));
            updated = new JSONObject(preferences.getString("updated", "{}"));
            invalidateOwnership();
        } catch (JSONException error) {
            items = new JSONArray();
            updated = new JSONObject();
            message = "上次活动缓存无法读取，请重新刷新活动。";
        }
    }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(244, 246, 252));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var system = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(system.left, system.top, system.right, system.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        catalogue = new WebView(this);
        configure(catalogue);
        var assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        catalogue.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().equals(APP_ORIGIN + "/assets/index.html");
            }
            @Override public void onPageFinished(WebView view, String url) {
                uiReady = true;
                publish();
            }
        });
        WebViewCompat.addWebMessageListener(catalogue, "PocketApp", java.util.Collections.singleton(APP_ORIGIN),
                (view, data, origin, main, reply) -> {
                    if (!main || !origin.toString().equals(APP_ORIGIN)) return;
                    try { handleCommand(new JSONObject(data.getData())); }
                    catch (JSONException error) { fail("应用消息格式无效，请重新打开程序。处理原因：" + error.getMessage()); }
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
        addButton(controls, "核实结果", this::verifyCurrent);
        addButton(controls, "跳过此项", this::skip);
        browser.addView(controls);
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
                if ("www.epicgames.com".equals(uri.getHost()) && uri.getPath() != null && uri.getPath().startsWith("/id/")) {
                    try { invalidateOwnership(); publish(); }
                    catch (JSONException error) { fail("拥有状态无法重置，请重新刷新活动。"); }
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    browserStatus.setText("网页加载失败：" + error.getDescription() + "。请返回列表后重试。");
                }
            }
        });
        WebViewCompat.addWebMessageListener(store, "PocketStore", STORE_ORIGINS,
                (view, data, origin, main, reply) -> {
                    if (active == null || !STORE_ORIGINS.contains(origin.toString())) return;
                    try { handleStoreMessage(new JSONObject(data.getData()), origin.toString(), main); }
                    catch (JSONException error) { fail("商店返回格式无法识别：" + error.getMessage()); }
                });
        browser.addView(store, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        browser.setVisibility(View.GONE);
        setContentView(root);
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

    private void addButton(LinearLayout row, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
    }

    private void handleCommand(JSONObject command) throws JSONException {
        String action = command.getString("action");
        if (active != null) return;
        if (action.equals("country")) {
            String choice = command.getString("country");
            if (!Arrays.asList("CN", "HK", "TW", "US", "JP", "GB", "DE").contains(choice)) return;
            country = choice;
            items = new JSONArray();
            updated = new JSONObject();
            message = "账号地区已更改，请刷新三个分类的活动。";
            save(); publish(); return;
        }
        if (action.equals("login")) {
            invalidateOwnership();
            message = "登录或管理账号后，请重新核实拥有状态。";
            String category = command.getString("category");
            openBrowser(category.equals("fab") ? "https://www.fab.com/login" :
                    "https://www.epicgames.com/id/login?lang=en-US&redirectUrl=https%3A%2F%2Fstore.epicgames.com%2Fen-US%2Ffree-games");
            browserStatus.setText("请在官方页面登录。完成后点“返回列表”，再刷新活动。");
            publish(); return;
        }
        if (action.equals("open")) {
            JSONObject item = find(command.getString("key"));
            if (item == null) return;
            invalidateOwnership();
            openBrowser(item.getString("url"));
            browserStatus.setText("官方商品页。可手动领取；返回后请核实拥有状态。");
            publish(); return;
        }
        if (action.equals("refresh")) {
            String category = command.getString("category");
            if (!Arrays.asList("pc", "android", "fab").contains(category)) return;
            tasks.add(new JSONObject().put("mode", "discover").put("category", category));
        } else if (action.equals("check") || action.equals("claim")) {
            JSONArray keys = command.getJSONArray("keys");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.getString(i);
                JSONObject item = find(key);
                if (item == null || !seen.add(key)) continue;
                if (action.equals("claim") && item.optString("status").equals("owned")) continue;
                tasks.add(new JSONObject().put("mode", action.equals("check") ? "check" : "claim")
                        .put("category", item.getString("category")).put("item", item));
            }
        }
        completed = 0;
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
        removeScript();
        store.stopLoading();
        active = tasks.poll();
        waitingToVerify = false;
        if (active == null) {
            message = "本次处理结束，共处理 " + completed + " 项。拥有状态以列表核实结果为准。";
            browser.setVisibility(View.GONE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            save(); publish(); return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startActive();
    }

    private void startActive() {
        try {
            active.put("nonce", UUID.randomUUID().toString()).put("country", country);
            script = WebViewCompat.addDocumentStartJavaScript(store,
                    scriptSource + "\nrunPocketTask(" + active + ");", STORE_ORIGINS);
            String category = active.getString("category");
            if (category.equals("pc") && active.getString("mode").equals("discover")) {
                message = "正在读取 Epic 电脑游戏官方活动…";
                publish();
                requestPcCatalogue(active.getString("nonce"));
                return;
            }
            String url = category.equals("fab") ? "https://www.fab.com/limited-time-free?lang=en" : "https://store.epicgames.com/en-US/free-games";
            String title = "读取活动";
            if (active.has("item")) {
                url = active.getJSONObject("item").getString("url");
                title = active.getJSONObject("item").getString("title");
            }
            browserStatus.setText((completed + 1) + "/" + total + "  " + title + "\n正在读取官方页面；需要验证时请在下方操作。");
            message = "正在处理：" + title;
            openBrowser(url);
            publish();
        } catch (JSONException error) { fail("任务数据无效：" + error.getMessage()); }
    }

    private void requestPcCatalogue(String nonce) {
        String requestedCountry = country;
        network.execute(() -> {
            try {
                String json = EpicPublicApi.promotions(requestedCountry);
                runOnUiThread(() -> {
                    if (active == null || !nonce.equals(active.optString("nonce"))) return;
                    String parse = "(()=>{try{return JSON.stringify({items:PocketDomain.parsePc(JSON.parse("
                            + JSONObject.quote(json) + "))});}catch(e){return JSON.stringify({error:e.message});}})()";
                    catalogue.evaluateJavascript(parse, result -> {
                        if (active == null || !nonce.equals(active.optString("nonce"))) return;
                        try {
                            Object decoded = new JSONTokener(result).nextValue();
                            if (!(decoded instanceof String)) throw new JSONException("活动解析器没有返回结果");
                            JSONObject data = new JSONObject((String) decoded);
                            if (data.has("error")) { fail(data.getString("error")); return; }
                            replaceCategory("pc", data.getJSONArray("items"));
                            updated.put("pc", System.currentTimeMillis());
                            completed++; next();
                        } catch (JSONException error) {
                            fail("电脑活动解析失败：" + error.getMessage());
                        }
                    });
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (active != null && nonce.equals(active.optString("nonce"))) fail("电脑活动读取失败：" + error.getMessage() + "。请检查网络后重试。");
                });
            }
        });
    }

    private void handleStoreMessage(JSONObject data, String origin, boolean main) throws JSONException {
        if (!data.optString("nonce").equals(active.optString("nonce"))) return;
        String type = data.getString("type");
        String category = active.getString("category");
        String productOrigin = category.equals("fab") ? "https://www.fab.com" : "https://store.epicgames.com";
        if (Arrays.asList("catalogue", "result", "error").contains(type) && (!main || !origin.equals(productOrigin))) return;
        if (type.equals("catalogue")) {
            replaceCategory(category, data.getJSONArray("items"));
            updated.put(category, System.currentTimeMillis());
            completed++; next();
        } else if (type.equals("result")) {
            String status = data.getString("status");
            if (!Arrays.asList("owned", "unowned", "expired").contains(status)) throw new JSONException("未知拥有状态");
            active.getJSONObject("item").put("status", status);
            completed++; next();
        } else if (type.equals("checkoutComplete")) {
            if (waitingToVerify) verifyCurrent();
        } else if (type.equals("ordered")) {
            if (!waitingToVerify) {
                waitingToVerify = true;
                browserStatus.setText("已点击零元订单，正在等待官方结果。若有验证码请完成，然后点“核实结果”。");
                // Keep the payment page alive; its captcha may still be pending.
            }
        } else if (type.equals("waiting")) {
            browserStatus.setText(data.getString("message") + "\n当前 " + (completed + 1) + "/" + total);
        } else if (type.equals("blocked")) {
            fail(data.getString("message"));
        } else if (type.equals("error")) {
            browserStatus.setText(data.getString("message") + "。处理网页后点“核实结果”，或返回列表重试。");
            if (active.has("item")) active.getJSONObject("item").put("status", "error");
            publish();
        }
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

    private void verifyCurrent() {
        if (active == null || !active.has("item")) {
            browserStatus.setText("请先完成网页登录，再返回列表刷新活动。");
            return;
        }
        try {
            removeScript();
            active.put("mode", "check");
            waitingToVerify = false;
            startActive();
        } catch (JSONException error) { fail("无法核实当前商品：" + error.getMessage()); }
    }

    private void skip() {
        if (active == null) { stop(); return; }
        completed++;
        next();
    }

    private void fail(String reason) {
        stop();
        message = reason;
        publish();
    }

    private void stop() {
        if (active != null) message = "已停止本次处理。尚未核实的订单，请在官方页面确认后再同步。";
        removeScript();
        tasks.clear();
        active = null;
        store.stopLoading();
        store.loadUrl("about:blank");
        browser.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CookieManager.getInstance().flush();
        save();
        publish();
    }

    private void removeScript() {
        if (script != null) { script.remove(); script = null; }
    }

    private void openBrowser(String url) {
        browser.setVisibility(View.VISIBLE);
        store.loadUrl(url);
    }

    private void invalidateOwnership() throws JSONException {
        for (int i = 0; i < items.length(); i++) items.getJSONObject(i).put("status", "unknown");
    }

    private void save() {
        getSharedPreferences("catalogue", MODE_PRIVATE).edit().putString("items", items.toString())
                .putString("updated", updated.toString()).putString("country", country).apply();
    }

    private void publish() {
        if (!uiReady) return;
        JSONObject state = new JSONObject();
        try {
            state.put("items", items).put("updated", updated).put("country", country)
                    .put("message", message).put("busy", active != null);
        } catch (JSONException error) { throw new IllegalStateException(error); }
        catalogue.evaluateJavascript("window.receiveState(" + state + ");", null);
    }

    @Override public void onBackPressed() {
        if (browser != null && browser.getVisibility() == View.VISIBLE) stop();
        else super.onBackPressed();
    }

    @Override public void onDestroy() {
        active = null;
        tasks.clear();
        network.shutdownNow();
        removeScript();
        if (store != null) { store.stopLoading(); store.destroy(); }
        if (catalogue != null) catalogue.destroy();
        super.onDestroy();
    }
}
