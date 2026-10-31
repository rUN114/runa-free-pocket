package cn.run.freepocket

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.ArrayDeque
import java.util.HashSet
import java.util.Objects
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

class MainActivity : Activity() {
    private val tasks = ArrayDeque<JSONObject>()
    private val network: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var items = JSONArray()
    private var updated = JSONObject()
    private var fabAccount = JSONObject()
    private lateinit var epic: EpicSession
    private var journal: ClaimJournal? = null
    private var journalError = ""
    private lateinit var images: OfficialImages
    private var country = "CN"
    private var fabTier = ""
    private var storeNavigation: Long = 0
    private var message = "正在读取本期免费内容…"
    private var completionNote = ""
    private var epicSyncError: String? = ""
    private var manualCategory: String? = null
    private lateinit var scriptSource: String
    private var active: JSONObject? = null
    private var script: ScriptHandler? = null
    private var readTimeout: Runnable? = null
    private var catalogue: WebView? = null
    private var store: WebView? = null
    private var browser: LinearLayout? = null
    private var browserStatus: TextView? = null
    private var browserAddress: TextView? = null
    private var verifyButton: Button? = null
    private var uiReady = false
    private var started = false
    private var waitingToVerify = false
    private var resettingAccount = false
    private var completed = 0
    private var total = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            AlertDialog.Builder(this).setTitle(R.string.webview_update_title)
                .setMessage(R.string.webview_update_message)
                .setPositiveButton(R.string.close) { _, _ -> finish() }.setCancelable(false).show()
            return
        }
        try {
            scriptSource = readAsset("domain.js") + "\n" + readAsset("automation.js")
            loadCache()
        } catch (error: IOException) {
            throw IllegalStateException("应用脚本资源缺失", error)
        }
        fabTier = getSharedPreferences("catalogue", MODE_PRIVATE).getString("fabTier", "")!!
        initializeSession()
        try {
            journal = ClaimJournal(getSharedPreferences("claim-journal", MODE_PRIVATE).getString("data", "{}")!!)
        } catch (_: JSONException) {
            journalError = "领取记录无法读取，为防止重复订单已停用自动领取，请保留应用数据并检查记录。"
        }
        images = OfficialImages(File(cacheDir, "official-images"))
        buildViews()
        catalogue!!.loadUrl(APP_ORIGIN + "/assets/index.html")
    }

    private fun initializeSession() {
        try {
            epic = EpicSession(this)
        } catch (_: Exception) {
            // A broken derived session can be replaced by a fresh official authorization, never plaintext storage.
            getSharedPreferences("epic-auth", MODE_PRIVATE).edit().remove("encrypted").apply()
            message = "本机授权记录无法读取，请重新连接 Epic 账号。"
            try {
                epic = EpicSession(this)
            } catch (unavailable: Exception) {
                throw IllegalStateException("无法初始化授权存储", unavailable)
            }
        }
    }

    @Throws(IOException::class)
    private fun readAsset(name: String): String {
        assets.open(name).use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                bytes.write(buffer, 0, count)
            }
            return bytes.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun loadCache() {
        val preferences = getSharedPreferences("catalogue", MODE_PRIVATE)
        country = preferences.getString("country", "CN")!!
        try {
            fabAccount = JSONObject(preferences.getString("fabAccount", "{}")!!)
            // Re-read derived metadata after promotion/ownership rules change; retain official sign-in.
            if (preferences.getInt("schema", 1) != 3) return
            items = JSONArray(preferences.getString("items", "[]")!!)
            updated = JSONObject(preferences.getString("updated", "{}")!!)
            for (i in 0 until items.length()) items.getJSONObject(i).put("status", "unknown")
        } catch (_: JSONException) {
            items = JSONArray()
            updated = JSONObject()
            message = "活动缓存无法读取，请重新刷新。"
        }
    }

    private fun buildViews() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(7, 19, 41))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val system = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(system.left, system.top, system.right, system.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val catalogueView = WebView(this)
        catalogue = catalogueView
        configure(catalogueView)
        catalogueView.setBackgroundColor(Color.rgb(7, 19, 41))
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        catalogueView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                val path = uri.path
                if (APP_ORIGIN == uri.scheme + "://" + uri.host && path != null && path.startsWith("/images/")) {
                    try {
                        val bytes = images.read(uri.lastPathSegment!!, "1" == uri.getQueryParameter("cover"))
                        return WebResourceResponse(OfficialImages.mime(bytes), null, ByteArrayInputStream(bytes))
                    } catch (error: IOException) {
                        Log.e("PocketImages", "Artwork load failed: " + error.message, error)
                        return WebResourceResponse("text/plain", "UTF-8", 502, "Image unavailable", emptyMap<String, String>(),
                            ByteArrayInputStream("图片读取失败，请重试或检查网络".toByteArray(StandardCharsets.UTF_8)))
                    }
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.toString() != APP_ORIGIN + "/assets/index.html"
            }

            override fun onPageFinished(view: WebView, url: String) {
                uiReady = true
                publish()
                if (started) return
                started = true
                try {
                    if (getSharedPreferences("catalogue", MODE_PRIVATE).getBoolean("accountResetPending", false)) {
                        resetAccount(false, "pc")
                        return
                    }
                    tasks.add(task("discover", "pc"))
                    if (epic.connected()) tasks.add(task("syncEpic", "pc"))
                    beginQueue()
                } catch (_: JSONException) {
                    fail("无法启动活动读取，请点击刷新重试。")
                }
            }
        }
        WebViewCompat.addWebMessageListener(catalogueView, "PocketApp", setOf(APP_ORIGIN)) { _, data, origin, main, _ ->
            if (!main || origin.toString() != APP_ORIGIN) return@addWebMessageListener
            val command = try {
                parseWebMessage(data)
            } catch (_: JSONException) {
                Log.w("PocketBridge", "Ignored malformed catalogue message")
                return@addWebMessageListener
            }
            try {
                handleCommand(command)
            } catch (rejected: ClaimJournal.Rejected) {
                message = rejected.reason
                publish()
            } catch (_: JSONException) {
                message = "应用命令无法处理，请刷新或检查领取记录。"
                publish()
            }
        }
        root.addView(catalogueView, FrameLayout.LayoutParams(-1, -1))
        val browserPanel = LinearLayout(this)
        browser = browserPanel
        browserPanel.orientation = LinearLayout.VERTICAL
        browserPanel.setBackgroundColor(Color.WHITE)
        val statusView = TextView(this)
        browserStatus = statusView
        statusView.setTextColor(Color.rgb(24, 35, 64))
        statusView.textSize = 14f
        statusView.setPadding(dp(14), dp(10), dp(14), dp(4))
        browserPanel.addView(statusView)
        val addressView = TextView(this)
        browserAddress = addressView
        addressView.textSize = 11f
        addressView.setTextColor(Color.rgb(89, 102, 126))
        addressView.setPadding(dp(14), 0, dp(14), dp(5))
        browserPanel.addView(addressView)
        val controls = LinearLayout(this)
        addButton(controls, getString(R.string.back_to_list)) { stop() }
        verifyButton = addButton(controls, getString(R.string.verify_result)) { verifyCurrent() }
        addButton(controls, getString(R.string.skip_item)) { skip() }
        browserPanel.addView(controls)
        addButton(controls, getString(R.string.network_diagnostics)) {
            val probe = AlertDialog.Builder(this).setTitle(R.string.diagnostics_title)
                .setMessage(R.string.diagnostics_loading).setPositiveButton(R.string.close, null).show()
            network.execute {
                val summary = StringBuilder(getString(R.string.diagnostics_intro))
                for (endpoint in listOf("https://www.fab.com/login", "https://www.epicgames.com/id/login", "https://www.epicgames.com/payment/")) {
                    var connection: java.net.HttpURLConnection? = null
                    val host = java.net.URI.create(endpoint).host
                    try {
                        connection = java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection
                        connection.connectTimeout = 8000
                        connection.readTimeout = 8000
                        connection.instanceFollowRedirects = false
                        connection.requestMethod = "HEAD"
                        summary.append("\n").append(host).append(": HTTP ").append(connection.responseCode)
                    } catch (error: IOException) {
                        summary.append("\n").append(host).append(": ").append(error.javaClass.simpleName)
                    } finally {
                        connection?.disconnect()
                    }
                }
                summary.append(getString(R.string.diagnostics_footer))
                handler.post { if (!isDestroyed && probe.isShowing) probe.setMessage(summary.toString()) }
            }
        }
        createStoreView()
        root.addView(browserPanel, FrameLayout.LayoutParams(-1, -1))
        browserPanel.visibility = View.GONE
        setContentView(root)
    }

    private fun createStoreView() {
        val storeView = WebView(this)
        store = storeView
        configure(storeView)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(storeView, true)
        storeView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return "https" != request.url.scheme
            }

            override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) {
                val navigation = ++storeNavigation
                handler.postDelayed({
                    if (navigation != storeNavigation || (store?.progress ?: 100) >= 100 || browser?.visibility != View.VISIBLE) return@postDelayed
                    browserStatus!!.text = getString(R.string.page_load_slow)
                }, 45000)
                val uri = Uri.parse(url)
                val currentTask = active
                if (currentTask != null && currentTask.optString("category") == "fab" && currentTask.optBoolean("fabTierVerified")) {
                    val productPath = Uri.parse(currentTask.optJSONObject("item")!!.optString("url")).path
                    val productPage = "www.fab.com" == uri.host && Objects.equals(productPath, uri.path)
                    val checkoutPage = ("www.fab.com" == uri.host && "/payment/web/purchase" == uri.path)
                        || ("www.epicgames.com" == uri.host && uri.path != null && uri.path!!.startsWith("/payment/"))
                    if (!productPage && !checkoutPage) currentTask.remove("fabTierVerified")
                }
                browserAddress!!.text = uri.host
                val login = "www.epicgames.com" == uri.host && uri.path != null && uri.path!!.startsWith("/id/login")
                if (!login || !epic.connected() || (currentTask != null && currentTask.optString("mode") == "auth")) return
                if ((currentTask != null && currentTask.optString("category") == "fab") || "fab" == manualCategory) return
                epic.disconnect()
                try {
                    invalidateOwnership(items, "epic")
                    publish()
                } catch (_: JSONException) {
                    fail("拥有状态无法重置，请重新刷新。")
                }
                if (currentTask != null) fail("Epic 网页登录已过期，请重新连接账号后继续领取。")
            }

            override fun onPageFinished(view: WebView, url: String) {
                storeNavigation++
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) browserStatus!!.text = getString(R.string.http_page_error, response.statusCode.toString(), request.url.host)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val currentTask = active
                var phase = getString(R.string.phase_browse)
                if (currentTask != null) {
                    phase = when (currentTask.optString("mode")) {
                        "auth" -> getString(R.string.phase_login)
                        "discover" -> getString(R.string.phase_discover)
                        "syncFab" -> getString(R.string.phase_library)
                        "claim" -> getString(R.string.phase_checkout)
                        else -> getString(R.string.phase_ownership)
                    }
                }
                val detail = error.description.toString()
                val codeMatch = Pattern.compile("net::ERR_[A-Z_]+").matcher(detail)
                val code = if (codeMatch.find()) codeMatch.group() else "WebView " + error.errorCode
                browserStatus!!.text = getString(R.string.page_load_error, phase, request.url.host, code)
                storeNavigation++
                if (currentTask != null && currentTask.optString("mode") in listOf("auth", "discover", "syncFab")
                    && error.errorCode in listOf(WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT)
                    && currentTask.optInt("readRetry") == 0) {
                    val retryTask = currentTask
                    try {
                        retryTask.put("readRetry", 1)
                    } catch (_: JSONException) {
                        fail("无法记录页面重试状态，请返回列表重试。")
                        return
                    }
                    browserStatus!!.append(getString(R.string.read_retry_once))
                    handler.postDelayed({ if (active === retryTask) verifyCurrent() }, 1500)
                }
            }
        }
        WebViewCompat.addWebMessageListener(storeView, "PocketStore", STORE_ORIGINS) { _, data, origin, main, reply ->
            val currentTask = active
            if (currentTask == null || !STORE_ORIGINS.contains(origin.toString())) return@addWebMessageListener
            val payload = try {
                parseWebMessage(data)
            } catch (_: JSONException) {
                Log.w("PocketBridge", "Ignored malformed store message")
                return@addWebMessageListener
            }
            try {
                handleStoreMessage(payload, origin.toString(), main, reply)
            } catch (_: JSONException) {
                fail("商店任务状态异常，已停止，请核实结果或刷新。")
            }
        }
        browser!!.addView(storeView, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun configure(view: WebView) {
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
    }

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private fun addButton(row: LinearLayout, text: String, action: Runnable): Button {
        val button = Button(this)
        button.text = text
        button.textSize = 13f
        button.isAllCaps = false
        button.setOnClickListener { action.run() }
        row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        return button
    }

    @Throws(JSONException::class)
    private fun task(mode: String, category: String): JSONObject =
        JSONObject().put("mode", mode).put("category", category)

    private fun validCategory(category: String): Boolean = category in listOf("pc", "android", "fab")

    @Throws(JSONException::class)
    private fun handleCommand(command: JSONObject) {
        if (active != null || resettingAccount) return
        val action = command.getString("action")
        if (action == "claimAll" || action == "resumeBatch") {
            if (journal == null || journalError.isNotEmpty()) { message = journalError; publish(); return }
            if (action == "claimAll") journal!!.start(country, listOf("pc", "android", "fab"), null)
            else journal!!.resume(country)
            if (!saveJournal()) return
            queueBatch()
            return
        }
        if (action == "fabTier") {
            val choice = command.getString("tier")
            if (choice !in listOf("personal", "professional", "")) return
            fabTier = choice
            getSharedPreferences("catalogue", MODE_PRIVATE).edit().putString("fabTier", choice).apply()
            publish(); return
        }
        if (action == "country") {
            val choice = command.getString("country")
            if (choice !in listOf("CN", "HK", "TW", "US", "JP", "GB", "DE")) return
            country = choice
            items = JSONArray()
            updated = JSONObject()
            tasks.add(task("discover", "pc"))
            if (epic.connected()) tasks.add(task("syncEpic", "pc"))
            save(); beginQueue(); return
        }
        if (action == "open") {
            val item = find(command.getString("key")) ?: return
            manualCategory = item.getString("category")
            verifyButton!!.isEnabled = true
            verifyButton!!.text = getString(R.string.sync_result)
            browserStatus!!.text = getString(R.string.official_product_note)
            openBrowser(item.getString("url"))
            return
        }
        val category = command.getString("category")
        if (!validCategory(category)) return
        if (action == "switchAccount" || action == "logout") {
            resetAccount(action == "switchAccount", category)
            return
        }
        if (action == "login") {
            if (category == "fab") { fabAccount = JSONObject(); invalidateOwnership(items, "fab") }
            else { epic.disconnect(); epicSyncError = ""; invalidateOwnership(items, "epic") }
            tasks.add(task("auth", category))
            tasks.add(task("discover", category))
            tasks.add(task(if (category == "fab") "syncFab" else "syncEpic", category))
        } else if (action == "refresh") {
            tasks.add(task("discover", category))
            if (category == "fab") {
                if (fabAccount.optBoolean("connected")) tasks.add(task("syncFab", category))
            } else if (epic.connected()) tasks.add(task("syncEpic", category))
        } else if (action == "check") {
            if (category == "fab" && !fabAccount.optBoolean("connected")) tasks.add(task("auth", category))
            if (category != "fab" && !epic.connected()) tasks.add(task("auth", category))
            tasks.add(task(if (category == "fab") "syncFab" else "syncEpic", category))
        } else if (action == "claim") {
            if (journal == null || journalError.isNotEmpty()) { message = journalError; publish(); return }
            val keys = command.getJSONArray("keys")
            val selected = JSONArray()
            val seen = HashSet<String>()
            for (i in 0 until keys.length()) {
                val item = find(keys.getString(i))
                if (item == null || category != item.getString("category") || !seen.add(item.getString("key"))) continue
                selected.put(JSONObject().put("key", item.getString("key")).put("title", item.getString("title")).put("category", category))
            }
            if (selected.length() == 0) return
            journal!!.start(country, listOf(category), selected)
            if (!saveJournal()) return
            queueBatch()
            return
        }
        beginQueue()
    }

    @Throws(JSONException::class)
    private fun queueBatch() {
        for (category in journal!!.categories()) {
            tasks.add(task("discover", category))
            tasks.add(task("ensureAuth", category))
            tasks.add(task(if (category == "fab") "syncFab" else "syncEpic", category).put("forClaim", true))
            tasks.add(task("planBatch", category))
        }
        beginQueue()
    }

    private fun saveJournal(): Boolean {
        // Do not acknowledge a page action until its recovery checkpoint is on disk.
        if (getSharedPreferences("claim-journal", MODE_PRIVATE).edit().putString("data", journal!!.serialize()).commit()) return true
        cancelReadTimeout()
        removeScript()
        tasks.clear()
        active = null
        store?.stopLoading()
        store?.loadUrl("about:blank")
        browser?.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        message = "无法保存领取进度，已停止自动操作。请检查手机存储空间后重试。"
        publish()
        return false
    }

    private fun bindBatchAccount(category: String): Boolean {
        val journal = journal ?: return true
        if (!journal.running()) return true
        return try {
            journal.bind(category, if (category == "fab") fabAccount.getString("accountId") else epic.accountId(),
                if (category == "fab") fabAccount.getString("displayName") else epic.displayName())
            true
        } catch (_: JSONException) {
            fail("当前账号未连接或与原领取记录不一致，请重新连接原账号，或开始新任务。")
            false
        }
    }

    private fun beginQueue() {
        completed = 0
        completionNote = ""
        total = tasks.size
        if (total > 0) next()
    }

    @Throws(JSONException::class)
    private fun find(key: String): JSONObject? {
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.getString("key") == key) return item
        }
        return null
    }

    private fun next() {
        cancelReadTimeout()
        removeScript()
        store?.stopLoading()
        active = tasks.poll()
        try {
            while (active != null) {
                val task = active!!
                val mode = task.getString("mode")
                val category = task.getString("category")
                if (mode == "ensureAuth") {
                    if (if (category == "fab") fabAccount.optBoolean("connected") else epic.connected()) {
                        completed++; active = tasks.poll(); continue
                    }
                    task.put("mode", "auth")
                    break
                }
                if (mode == "planBatch") {
                    if (!bindBatchAccount(category)) return
                    journal!!.plan(category, items, System.currentTimeMillis())
                    val work = journal!!.work(category)
                    for (i in work.indices.reversed()) {
                        val entry = work[i]
                        val item = find(entry.getString("key"))
                        if (item != null) {
                            val entryMode = when {
                                entry.getString("status") == "uncertain" -> if (category == "fab") "check" else "verifyEpic"
                                entry.optBoolean("pageCheck") -> "check"
                                else -> "claim"
                            }
                            tasks.addFirst(task(entryMode, category).put("item", item)
                                .put("precheck", entry.getString("status") == "pending" && entry.optBoolean("pageCheck")))
                        }
                    }
                    total += work.size
                    if (!saveJournal()) return
                    completed++; active = tasks.poll(); continue
                }
                if (mode != "claim" && mode != "check" && mode != "verifyEpic") break
                if (!task.has("item")) break
                val current = find(task.getJSONObject("item").getString("key"))
                // Login expiry can invalidate an item after the batch was planned.
                if (mode == "claim" && current != null &&
                    (current.optString("status") !in listOf("unowned", "partial") || current.optBoolean("cached"))) {
                    val journal = journal
                    if (journal != null && journal.running()) {
                        var resultStatus = current.optString("status")
                        if (current.optBoolean("cached")) resultStatus = "error"
                        journal.result(current.getString("key"), resultStatus, "拥有状态已变化，请先同步库存再重试")
                        if (!saveJournal()) return
                    }
                    completed++; active = tasks.poll(); continue
                }
                if (current != null && (mode != "claim" || claimableNow(current, System.currentTimeMillis()))) {
                    task.put("item", current)
                    val journal = journal
                    if (journal != null && journal.running()) {
                        journal.working(current.getString("key"))
                        if (!saveJournal()) return
                    }
                    break
                }
                val journal = journal
                if (journal != null && journal.running()) {
                    journal.result(task.getJSONObject("item").getString("key"),
                        if (current == null) "expired" else current.optString("status"), "当前活动或拥有状态已变化")
                    if (!saveJournal()) return
                }
                completed++
                active = tasks.poll()
            }
        } catch (_: JSONException) {
            fail("领取队列无法读取，请重新选择。")
            return
        }
        waitingToVerify = false
        if (active == null) {
            try {
                val journal = journal
                if (journal != null && journal.running()) {
                    journal.finish()
                    if (!saveJournal()) return
                    completionNote = "本次领取处理已结束，请查看领取记录；待核实项目不会重复自动下单。"
                }
            } catch (_: JSONException) {
                message = "领取结果记录异常，请同步库存核实。"; publish(); return
            }
            message = if (completionNote.isEmpty()) "活动已更新。连接账号后，拥有状态会自动同步。" else completionNote
            browser?.visibility = View.GONE
            store?.loadUrl("about:blank")
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            save(); publish(); return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startActive()
    }

    private fun startActive() {
        try {
            val task = active!!
            task.put("nonce", UUID.randomUUID().toString()).put("country", country)
            val nonce = task.getString("nonce")
            val category = task.getString("category")
            val mode = task.getString("mode")
            if (task.has("item") && journal?.running() == true) {
                if (!bindBatchAccount(category)) return
                if (category == "fab") task.put("accountId", fabAccount.getString("accountId"))
            }
            verifyButton!!.isEnabled = true
            verifyButton!!.text = if (mode == "auth") getString(R.string.retry_login) else getString(R.string.retry_read)
            if (mode == "syncEpic" || mode == "verifyEpic") {
                browser?.visibility = View.GONE
                store?.loadUrl("about:blank")
                message = "正在同步 Epic 账号的完整拥有凭证…"
                publish(); requestEpicOwnership(nonce); return
            }
            if (category == "pc" && mode == "discover") {
                browser?.visibility = View.GONE
                store?.loadUrl("about:blank")
                message = "正在读取 Epic 电脑游戏官方活动…"
                publish(); requestPcCatalogue(nonce); return
            }
            if (category == "fab" && mode == "claim") {
                if (fabTier.isEmpty()) { fail("请在账号与同步中明确选择 Fab 许可档位后重试。"); return }
                val currentRun = journal!!.current()!!
                val previousTier = currentRun.optString("fabTier")
                if (previousTier.isNotEmpty() && previousTier != fabTier) { fail("当前许可档位与原任务不一致，请恢复原档位或开始新任务。"); return }
                currentRun.put("fabTier", fabTier)
                task.put("fabTier", fabTier)
                if (!saveJournal()) return
            }
            script = WebViewCompat.addDocumentStartJavaScript(store!!, scriptSource + "\nrunPocketTask(" + task + ");", STORE_ORIGINS)
            var url = if (category == "fab") "https://www.fab.com/limited-time-free?lang=en" else "https://store.epicgames.com/en-US/free-games"
            var title = getString(R.string.phase_discover)
            if (mode == "auth") {
                url = if (category == "fab") "https://www.fab.com/login" else EpicSession.authorizationUrl()
                title = getString(R.string.login_task_title)
            } else if (mode == "syncFab") title = getString(R.string.fab_library_task_title)
            if (task.has("item")) {
                verifyButton!!.text = getString(R.string.verify_result)
                url = task.getJSONObject("item").getString("url")
                title = task.getJSONObject("item").getString("title")
            }
            browserStatus!!.text = getString(R.string.task_status, (completed + 1).toString(), total.toString(), title)
            message = "正在处理：$title"
            openBrowser(url)
            if (mode == "discover" || mode == "syncFab") scheduleReadTimeout(nonce)
            publish()
        } catch (_: JSONException) {
            fail("任务数据无效，请重新刷新。")
        }
    }

    private fun interface ItemsCallback {
        fun accept(value: JSONArray)
    }

    private fun evaluateItems(expression: String, nonce: String, callback: ItemsCallback) {
        val code = "(()=>{try{return JSON.stringify({items:" + expression +
            "});}catch(e){return JSON.stringify({error:e.message});}})()"
        catalogue!!.evaluateJavascript(code) { raw ->
            if (!current(nonce)) return@evaluateJavascript
            try {
                val decoded = JSONTokener(raw).nextValue()
                if (decoded !is String) throw JSONException("解析器没有返回结果")
                val result = JSONObject(decoded)
                if (result.has("error")) throw JSONException(result.getString("error"))
                callback.accept(result.getJSONArray("items"))
            } catch (error: JSONException) {
                fail("数据解析失败：" + error.message)
            }
        }
    }

    private fun current(nonce: String): Boolean = active != null && nonce == active!!.optString("nonce")

    private fun requestPcCatalogue(nonce: String) {
        val requestedCountry = country
        network.execute {
            try {
                val json = EpicPublicApi.promotions(requestedCountry)
                runOnUiThread {
                    if (!current(nonce)) return@runOnUiThread
                    evaluateItems("PocketDomain.parsePc(JSON.parse(" + JSONObject.quote(json) + "))", nonce) { fresh ->
                        replaceCategory("pc", fresh)
                        updated.put("pc", System.currentTimeMillis())
                        finishCatalogue(nonce)
                    }
                }
            } catch (_: IOException) {
                runOnUiThread { if (current(nonce)) fail("电脑活动读取失败，请检查网络后重试。") }
            }
        }
    }

    private fun finishCatalogue(nonce: String) {
        val cached = epic.cachedSnapshot()
        val category = active!!.optString("category")
        if (cached == null || category == "fab") { completed++; next(); return }
        evaluateItems("PocketDomain.applyOwnership(" + items + "," + cached + ",false," + JSONObject.quote(category) + ")", nonce) { value ->
            items = value
            completed++; next()
        }
    }

    private fun acceptAuthorization(code: String, nonce: String) {
        network.execute {
            try {
                val session = EpicSession.exchange(code)
                runOnUiThread {
                    if (!current(nonce)) return@runOnUiThread
                    try {
                        epic.save(session); epicSyncError = ""; completed++; next()
                    } catch (_: GeneralSecurityException) {
                        fail("无法安全保存授权，请重新连接账号。")
                    } catch (_: IOException) {
                        fail("无法安全保存授权，请重新连接账号。")
                    }
                }
            } catch (error: IOException) {
                runOnUiThread { if (current(nonce)) fail("账号授权请求失败：" + error.message) }
            } catch (_: JSONException) {
                runOnUiThread { if (current(nonce)) fail("未取得有效的库存授权，请重新连接 Epic 账号。") }
            }
        }
    }

    private fun requestEpicOwnership(nonce: String) {
        val category = active!!.optString("category")
        val requested = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)
            if (item != null && category == item.optString("category")) requested.put(item)
        }
        val requestedJson = requested.toString()
        network.execute {
            try {
                val result = epic.readOwnership(JSONArray(requestedJson))
                runOnUiThread {
                    if (!current(nonce)) return@runOnUiThread
                    try {
                        epic.save(result.session)
                        epicSyncError = ""
                        evaluateItems("PocketDomain.applyOwnership(" + items + "," + result.snapshot + ",true," + JSONObject.quote(category) + ")", nonce) { value ->
                            items = value
                            val task = active!!
                            if (task.optBoolean("forClaim") && !bindBatchAccount(category)) return@evaluateItems
                            completionNote = "拥有状态已同步：" + epic.displayName() + "。已拥有的项目会跳过。"
                            if (task.has("item")) {
                                val checked = find(task.getJSONObject("item").getString("key"))
                                if (!task.optBoolean("precheck") && checked != null && checked.optString("status") != "owned") {
                                    if (task.optInt("verifyAttempt", 0) == 0) {
                                        task.put("verifyAttempt", 1)
                                        message = "订单已提交，等待拥有凭证更新…"
                                        readTimeout = Runnable { if (current(nonce)) requestEpicOwnership(nonce) }
                                        handler.postDelayed(readTimeout!!, 3000)
                                        publish(); return@evaluateItems
                                    }
                                    checked.put("status", "error").put("statusReason", "订单尚未被账号拥有凭证确认")
                                    completionNote = "有订单尚未确认，请查看卡片状态后重试同步。"
                                }
                            }
                            if (task.has("item")) {
                                val checked = find(task.getJSONObject("item").getString("key"))
                                if (!recordBatchItem(checked, checked!!.optString("status"), checked.optString("statusReason"))) return@evaluateItems
                            }
                            completed++; next()
                        }
                    } catch (_: GeneralSecurityException) {
                        fail("无法安全保存账号会话，请重新连接 Epic。")
                    } catch (_: IOException) {
                        fail("无法安全保存账号会话，请重新连接 Epic。")
                    }
                }
            } catch (error: IOException) {
                runOnUiThread { if (current(nonce)) syncFailed(error.message) }
            } catch (error: JSONException) {
                runOnUiThread { if (current(nonce)) syncFailed(error.message) }
            }
        }
    }

    @Throws(JSONException::class)
    private fun handleStoreMessage(data: JSONObject, origin: String, main: Boolean, reply: JavaScriptReplyProxy) {
        if (!current(data.optString("nonce"))) return
        val task = active!!
        val category = task.getString("category")
        val mode = task.getString("mode")
        if (!StoreMessagePolicy.accepts(category, mode, data, origin, main)) return
        try {
            StoreMessagePolicy.validate(category, data)
        } catch (_: JSONException) {
            // Never include payloads or parser exceptions: they may contain authorization data.
            Log.w("PocketBridge", "Ignored incomplete store message")
            return
        }
        val type = data.getString("type")
        if (type == "licenseInvalidated") {
            task.remove("fabTierVerified")
            fail("商品页许可在领取过程中发生变化，已停止；请先核实订单结果。"); return
        }
        if (type == "actionReady") {
            if (mode != "claim" || !task.has("item") || journal?.running() != true) return
            val stage = data.getString("stage")
            if (!bindBatchAccount(category)) return
            if (category == "fab") {
                if (stage == "product") {
                    if (task.getString("fabTier") != data.optString("fabTier")) {
                        fail("商品页许可与所选档位不一致，未打开结算。"); return
                    }
                    task.put("fabTierVerified", true)
                    journal!!.entry(task.getJSONObject("item").getString("key"))!!.put("selectedFabTier", task.getString("fabTier"))
                } else if (!task.optBoolean("fabTierVerified")) {
                    fail("未取得本次商品页许可确认，未提交订单。"); return
                }
            }
            try {
                journal!!.reserve(task.getJSONObject("item").getString("key"), stage)
            } catch (_: JSONException) {
                fail("此项目存在待核实操作或重复请求，请先在领取记录中核实。"); return
            }
            if (!saveJournal()) return
            if (category == "fab" && stage == "product") {
                removeScript()
                script = WebViewCompat.addDocumentStartJavaScript(store!!, scriptSource + "\nrunPocketTask(" + task + ");", STORE_ORIGINS)
            }
            reply.postMessage(JSONObject().put("fabTierVerified", task.optBoolean("fabTierVerified"))
                .put("type", "actionPermit").put("nonce", task.getString("nonce")).put("stage", stage).toString())
            return
        }
        if (type == "authorized") {
            val code = data.getString("code")
            if (!code.matches("[a-zA-Z0-9_-]{16,1024}".toRegex())) return
            task.put("mode", "authorizing")
            verifyButton!!.isEnabled = false
            browserStatus!!.text = getString(R.string.authorizing)
            acceptAuthorization(code, task.getString("nonce"))
        } else if (type == "authenticated") {
            fabAccount.put("connected", true).put("displayName", data.getString("displayName"))
            completed++; next()
        } else if (type == "ownership") {
            acceptFabOwnership(data)
            if (task.optBoolean("forClaim") && !bindBatchAccount(category)) return
            completed++; next()
        } else if (type == "catalogue") {
            replaceCategory(category, data.getJSONArray("items"))
            updated.put(category, System.currentTimeMillis())
            finishCatalogue(task.getString("nonce"))
        } else if (type == "result") {
            if (mode !in listOf("claim", "check") || !task.has("item")) return
            val status = data.getString("status")
            val item = find(task.getJSONObject("item").getString("key"))
            if (item != null) item.put("status", status).put("checkedAt", System.currentTimeMillis()).put("cached", false)
                .put("statusReason", if (category == "fab") "Fab 素材库已核实" else "官方商品页面已核实")
            if (!recordBatchItem(item, status, data.optString("message", "官方页面及库存已核实"))) return
            completed++; next()
        } else if (type == "checkoutComplete") {
            if (mode == "claim") verifyCurrent()
        } else if (type == "ordered") {
            if (mode == "claim" && !waitingToVerify) {
                waitingToVerify = true
                browserStatus!!.text = getString(R.string.order_submitted)
            }
        } else if (type == "waiting") {
            browserStatus!!.text = getString(R.string.task_progress, data.getString("message"), (completed + 1).toString(), total.toString())
        } else if (type == "blocked") {
            if (mode == "claim") fail(data.getString("message"))
        } else if (type == "error") {
            if (mode == "syncFab" || mode == "discover") {
                cancelReadTimeout()
                if (mode == "syncFab") markSyncError(data.getString("message"))
                else completionNote = "活动读取失败：" + data.getString("message")
                browserStatus!!.text = getString(R.string.read_failure_help, data.getString("message"))
                publish(); return
            }
            browserStatus!!.text = getString(R.string.item_failure_help, data.getString("message"))
            if (task.has("item")) task.getJSONObject("item").put("status", "error").put("statusReason", data.getString("message"))
            publish()
        }
    }

    @Throws(JSONException::class)
    private fun recordBatchItem(item: JSONObject?, status: String, reason: String): Boolean {
        val journal = journal ?: return true
        if (!journal.running()) return true
        val key = active!!.getJSONObject("item").getString("key")
        if (active!!.optBoolean("precheck") && status == "unowned" && item != null && claimableNow(item, System.currentTimeMillis())) {
            journal.prechecked(key)
            tasks.addFirst(task("claim", active!!.getString("category")).put("item", item))
            total++
        } else journal.result(key, status, reason)
        return saveJournal()
    }

    @Throws(JSONException::class)
    private fun acceptFabOwnership(data: JSONObject) {
        val acquired = data.getJSONArray("owned")
        val uids = HashSet<String>()
        for (i in 0 until acquired.length()) uids.add(acquired.getString(i))
        val time = data.getLong("checkedAt")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.getString("category") == "fab") item.put("status", if (item.getString("id") in uids) "owned" else "unowned")
                .put("checkedAt", time).put("cached", false).put("statusReason", "Fab 已获取素材库核实")
        }
        fabAccount = JSONObject().put("connected", true).put("displayName", data.getString("displayName"))
            .put("accountId", data.getString("accountId")).put("checkedAt", time)
        completionNote = "Fab 拥有状态已同步，已拥有的素材会跳过。"
    }

    private fun syncFailed(reason: String?) {
        try {
            markSyncError(reason)
            if (active!!.optBoolean("forClaim") || active!!.optString("mode") == "verifyEpic") { fail(completionNote); return }
            completed++; next()
        } catch (_: JSONException) {
            fail("同步结果无法更新，请重新刷新。")
        }
    }

    @Throws(JSONException::class)
    private fun markSyncError(reason: String?) {
        val task = active!!
        val fab = task.optString("category") == "fab"
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val fabItem = item.getString("category") == "fab"
            if (fabItem != fab || item.getString("category") != task.optString("category")) continue
            item.put("statusReason", reason).put("cached", true)
            if (item.optString("status") != "owned") item.put("status", "error")
        }
        completionNote = "拥有状态同步失败：$reason"
        if (fab) fabAccount.put("error", reason) else epicSyncError = reason
    }

    @Throws(JSONException::class)
    private fun replaceCategory(category: String, fresh: JSONArray) {
        val merged = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.getString("category") != category) merged.put(item)
        }
        val keys = HashSet<String>()
        for (i in 0 until fresh.length()) {
            val item = fresh.getJSONObject(i)
            val uri = Uri.parse(item.getString("url"))
            val expected = if (category == "fab") "www.fab.com" else "store.epicgames.com"
            if (category != item.getString("category") || "https" != uri.scheme
                || expected != uri.host) throw JSONException("商品地址不属于官方商城")
            if (keys.add(item.getString("key"))) merged.put(item.put("status", "unknown"))
        }
        items = merged
        save()
    }

    private fun scheduleReadTimeout(nonce: String) {
        readTimeout = Runnable {
            if (!current(nonce)) return@Runnable
            browserStatus!!.text = getString(R.string.read_timeout)
        }
        handler.postDelayed(readTimeout!!, 40000)
    }

    private fun cancelReadTimeout() {
        val timeout = readTimeout ?: return
        handler.removeCallbacks(timeout)
        readTimeout = null
    }

    private fun verifyCurrent() {
        val task = active
        if (task == null || !task.has("item")) {
            if (task == null) { stop(); return }
            cancelReadTimeout(); removeScript(); startActive()
            return
        }
        try {
            removeScript()
            val catalogItems = task.getJSONObject("item").optJSONArray("catalogItems")
            val pageOnly = task.getString("category") == "fab" || catalogItems == null || catalogItems.length() == 0
            task.put("mode", if (pageOnly) "check" else "verifyEpic")
            waitingToVerify = false
            startActive()
        } catch (_: JSONException) {
            fail("无法核实当前商品，请重新同步。")
        }
    }

    private fun skip() {
        val task = active ?: run { stop(); return }
        if (task.optString("mode") != "claim" && task.optString("mode") != "check") { stop(); return }
        try {
            val journal = journal
            if (journal != null && journal.running() && task.has("item")) {
                journal.result(task.getJSONObject("item").getString("key"), "error", "用户跳过，可在记录中重试未提交项目")
                if (!saveJournal()) return
            }
        } catch (_: JSONException) {
            fail("无法记录跳过结果，请同步库存。"); return
        }
        completed++
        next()
    }

    private fun fail(reason: String) {
        manualCategory = null
        val journal = journal
        val task = active
        if (journal != null && journal.running() && task != null) {
            try {
                if (task.has("item")) journal.result(task.getJSONObject("item").getString("key"), "error", reason)
                else {
                    val category = task.getString("category")
                    journal.categoryFailed(category, reason)
                    tasks.removeIf { category == it.optString("category") }
                }
                if (!saveJournal()) return
                completionNote = reason
                completed++; next(); return
            } catch (_: JSONException) {
                message = "领取记录无法更新，已停止。请同步库存核实结果。"
            }
        }
        stop()
        message = reason
        publish()
    }

    private fun stop() {
        val returnCategory = if (active == null) manualCategory else null
        manualCategory = null
        val journal = journal
        if (journal != null && journal.running()) {
            try {
                journal.pause("已暂停；继续前会重新查库，待核实订单不会自动重下单")
            } catch (_: JSONException) {
                journalError = "领取记录无法暂停，请同步库存核实。"
            }
            if (!saveJournal()) return
        }
        if (active != null) message = if (completionNote.isEmpty()) "本次处理已停止。未确认的订单可点击同步重新核实。" else completionNote
        cancelReadTimeout()
        removeScript()
        tasks.clear()
        active = null
        store?.stopLoading()
        store?.loadUrl("about:blank")
        browser?.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        CookieManager.getInstance().flush()
        save(); publish()
        if (returnCategory == null) return
        try {
            if (returnCategory == "fab") tasks.add(task("syncFab", "fab"))
            else {
                if (!epic.connected()) tasks.add(task("auth", returnCategory))
                tasks.add(task("syncEpic", returnCategory))
            }
            beginQueue()
        } catch (_: JSONException) {
            message = "自动同步未启动，请点击同步按钮。"; publish()
        }
    }

    private fun removeScript() {
        script?.remove()
        script = null
    }

    private fun openBrowser(url: String) {
        browser!!.visibility = View.VISIBLE
        store!!.loadUrl(url)
    }

    @Throws(JSONException::class)
    private fun resetAccount(reconnect: Boolean, category: String) {
        // Epic and Fab share this app's browser cookies. Switching must clear both identities.
        invalidateOwnership(items, "all")
        val journal = journal
        if (journal != null) {
            if (journal.running()) journal.pause("账号已退出，继续任务前必须登录原账号")
            if (!getSharedPreferences("claim-journal", MODE_PRIVATE).edit().putString("data", journal.serialize()).commit())
                journalError = "无法保存领取记录，已停用自动领取；退出登录仍会继续。请释放存储空间后重新打开。"
        }
        resettingAccount = true
        epic.disconnect()
        fabAccount = JSONObject()
        epicSyncError = ""
        completionNote = ""
        manualCategory = null
        message = "正在退出旧账号并清理登录状态…"
        cancelReadTimeout()
        removeScript()
        tasks.clear()
        store?.stopLoading()
        browser?.removeView(store)
        store?.clearHistory()
        store?.clearCache(true)
        store?.destroy()
        store = null
        browser?.visibility = View.GONE
        WebStorage.getInstance().deleteAllData()
        // Persist the pending reset so an interrupted cleanup completes before any future login.
        save()
        publish()
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            if (isDestroyed) return@removeAllCookies
            createStoreView()
            resettingAccount = false
            message = "已退出 Epic 和 Fab。登录后会重新核实当前账号的拥有状态。"
            save()
            publish()
            if (!reconnect) return@removeAllCookies
            try {
                tasks.add(task("auth", category))
                tasks.add(task("discover", category))
                tasks.add(task(if (category == "fab") "syncFab" else "syncEpic", category))
                beginQueue()
            } catch (_: JSONException) {
                fail("已退出旧账号，请重新点击连接账号。")
            }
        }
    }

    private fun save() {
        getSharedPreferences("catalogue", MODE_PRIVATE).edit().putInt("schema", 3).putString("items", items.toString())
            .putString("updated", updated.toString()).putString("country", country).putString("fabAccount", fabAccount.toString())
            .putBoolean("accountResetPending", resettingAccount).apply()
    }

    private fun publish() {
        if (!uiReady) return
        try {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                item.put("localCover", images.register(item.optString("cover"), true))
                val local = JSONArray()
                val screenshots = item.optJSONArray("screenshots")
                if (screenshots != null) for (j in 0 until screenshots.length()) local.put(images.register(screenshots.optString(j), false))
                item.put("localScreenshots", local)
            }
            val cached = epic.cachedSnapshot()
            val account = JSONObject().put("connected", epic.connected()).put("displayName", epic.displayName())
                .put("checkedAt", if (cached == null) 0 else cached.optLong("checkedAt")).put("error", epicSyncError)
            val fabStatus = JSONObject(fabAccount.toString())
            fabStatus.remove("accountId")
            val state = JSONObject().put("items", items).put("updated", updated).put("country", country).put("fabTier", fabTier)
                .put("message", message).put("busy", active != null || resettingAccount).put("fabAccount", fabStatus).put("epicAccount", account)
                .put("history", if (journal == null) JSONArray() else journal!!.publicRuns()).put("journalError", journalError)
            catalogue!!.evaluateJavascript("window.receiveState(" + state + ");", null)
        } catch (error: JSONException) {
            throw IllegalStateException(error)
        }
    }

    override fun onBackPressed() {
        val browser = browser
        if (browser != null && browser.visibility == View.VISIBLE) stop()
        else if (uiReady) catalogue!!.evaluateJavascript("window.closeDetail && window.closeDetail()") { closed ->
            if (closed != "true") finish()
        }
        else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (catalogue != null && uiReady && active == null && manualCategory == null && !resettingAccount)
            catalogue!!.evaluateJavascript("window.refreshIfDue && window.refreshIfDue()", null)
    }

    override fun onDestroy() {
        val journal = journal
        if (journal != null && journal.running()) {
            try {
                journal.pause("应用已关闭，继续前将重新核实库存")
                getSharedPreferences("claim-journal", MODE_PRIVATE).edit().putString("data", journal.serialize()).commit()
            } catch (error: JSONException) {
                Log.e("ClaimJournal", "Unable to checkpoint closing batch", error)
            }
        }
        active = null
        tasks.clear()
        cancelReadTimeout()
        network.shutdownNow()
        removeScript()
        store?.stopLoading()
        store?.destroy()
        catalogue?.destroy()
        super.onDestroy()
    }

    companion object {
        @Throws(JSONException::class)
        internal fun parseWebMessage(message: WebMessageCompat): JSONObject {
            if (message.type != WebMessageCompat.TYPE_STRING) throw JSONException("网页消息必须是文本")
            val text = message.data ?: throw JSONException("网页消息缺少文本")
            return JSONObject(text)
        }

        private const val APP_ORIGIN = "https://appassets.androidplatform.net"
        private val STORE_ORIGINS: Set<String> = StoreMessagePolicy.origins

        @JvmStatic
        fun claimableNow(item: JSONObject, now: Long): Boolean {
            if (item.optString("status") in listOf("owned", "expired")) return false
            if (item.optString("availability") == "expired") return false
            if (item.optString("availability") == "upcoming" && item.optString("start").isEmpty()) return false
            return try {
                val start = item.opt("start")
                if (start != null && start !== JSONObject.NULL && start.toString().isNotEmpty()) {
                    val time = if (start is Number) start.toLong() else java.time.Instant.parse(start.toString()).toEpochMilli()
                    if (time > now) return false
                }
                val end = item.optString("end")
                end.isEmpty() || java.time.Instant.parse(end).toEpochMilli() > now
            } catch (_: java.time.format.DateTimeParseException) {
                false
            }
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun invalidateOwnership(items: JSONArray, category: String) {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val itemCategory = item.getString("category")
                val matches = category == "all" || category == itemCategory
                    || (category == "epic" && itemCategory != "fab")
                if (!matches) continue
                item.put("status", "unknown")
                item.remove("checkedAt")
                item.remove("cached")
                item.remove("statusReason")
            }
        }
    }
}
