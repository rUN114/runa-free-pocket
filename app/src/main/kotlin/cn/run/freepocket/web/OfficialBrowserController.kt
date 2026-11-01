package cn.run.freepocket.web

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import cn.run.freepocket.R
import cn.run.freepocket.StoreMessagePolicy
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Official pages own login and checkout; the caller owns tasks, accounts and claim checkpoints. */
class OfficialBrowserController(
    private val activity: Activity,
    private val onMessage: (JSONObject, String, Boolean, (JSONObject) -> Unit) -> Unit,
    private val onBack: () -> Unit,
    private val onRetry: () -> Unit,
    private val onSkip: () -> Unit,
    private val onNavigation: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val panel = LinearLayout(activity)
    private val title = TextView(activity)
    private val status = TextView(activity)
    private val address = TextView(activity)
    private val retry: Button
    private var webView: WebView? = null
    private var request: BrowserRequest? = null
    private var scriptHandler: ScriptHandler? = null
    private var readTimeout: Runnable? = null
    private var progressText = ""
    private var readOverdue = false
    private var navigation = 0L
    private var generation = 0L
    private var retried = false
    private var closed = false
    private var clearingSession = false
    private var diagnosticDialog: AlertDialog? = null

    val view: View get() = panel

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.WHITE)
        panel.visibility = View.GONE
        title.setTextColor(Color.rgb(24, 35, 64))
        title.textSize = 14f
        title.setPadding(dp(14), dp(10), dp(14), dp(4))
        panel.addView(title)
        status.setTextColor(Color.rgb(24, 35, 64))
        status.textSize = 14f
        status.setPadding(dp(14), 0, dp(14), dp(4))
        panel.addView(status)
        address.textSize = 11f
        address.setTextColor(Color.rgb(89, 102, 126))
        address.setPadding(dp(14), 0, dp(14), dp(5))
        panel.addView(address)
        val controls = LinearLayout(activity)
        addButton(controls, R.string.back_to_list) { onBack() }
        retry = addButton(controls, R.string.verify_result) { onRetry() }
        addButton(controls, R.string.skip_item) { onSkip() }
        panel.addView(controls)
        val diagnostics = LinearLayout(activity)
        addButton(diagnostics, R.string.network_diagnostics) { diagnose() }
        panel.addView(diagnostics)
    }

    /** A refreshed script must be installed before replying to Fab's product-stage permit. */
    fun show(next: BrowserRequest): Boolean {
        if (closed || clearingSession || !BrowserPagePolicy.canStart(next.url)) return false
        val previous = request
        if (previous?.id == next.id) {
            if (previous.script != next.script) installScript(webView!!, next.script)
            request = next
            updateTitle(next)
            return true
        }
        disposePage(clearCache = false)
        request = next
        retried = false
        val page = createPage(next.id, generation)
        webView = page
        panel.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        installScript(page, next.script)
        updateTitle(next)
        status.text = ""
        address.text = BrowserPagePolicy.displayHost(next.url)
        panel.visibility = View.VISIBLE
        page.loadUrl(next.url)
        if (next.mode in setOf("discover", "syncFab")) {
            val token = generation
            val timeout = Runnable {
                if (isCurrent(page, next.id, token)) {
                    readOverdue = true
                    renderStatus()
                }
                readTimeout = null
            }
            readTimeout = timeout
            handler.postDelayed(timeout, 40000)
        }
        return true
    }

    fun hide() {
        disposePage(clearCache = false)
        panel.visibility = View.GONE
        CookieManager.getInstance().flush()
    }

    fun setStatus(message: String) {
        if (closed) return
        progressText = message
        renderStatus()
    }

    private fun renderStatus() {
        status.text = when {
            !readOverdue -> progressText
            progressText.isBlank() -> activity.getString(R.string.read_timeout)
            else -> progressText + "\n" + activity.getString(R.string.read_timeout)
        }
    }

    /** The owner persists its pending-reset flag before calling this asynchronous cleanup. */
    fun clearSession(onComplete: (Boolean) -> Unit) {
        if (closed || clearingSession) {
            onComplete(false)
            return
        }
        clearingSession = true
        if (webView == null) {
            val cacheView = WebView(activity)
            cacheView.clearCache(true)
            cacheView.destroy()
        }
        disposePage(clearCache = true)
        panel.visibility = View.GONE
        WebStorage.getInstance().deleteAllData()
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            if (closed) return@removeAllCookies
            clearingSession = false
            // removeAllCookies(false) also means that there were no cookies to remove.
            onComplete(!cookies.hasCookies())
        }
    }

    fun close() {
        if (closed) return
        closed = true
        disposePage(clearCache = false)
        panel.visibility = View.GONE
        network.shutdownNow()
    }

    private fun createPage(id: String, token: Long): WebView {
        val page = WebView(activity)
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
            // Epic's checkout needs window.open routed into this official page.
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(page, true)
        }
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !BrowserPagePolicy.canNavigate(request.url.toString())

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!isCurrent(page, id, token)) return
                val navigationToken = ++navigation
                address.text = BrowserPagePolicy.displayHost(url)
                handler.postDelayed({
                    if (isCurrent(page, id, token) && navigation == navigationToken && page.progress < 100)
                        status.setText(R.string.page_load_slow)
                }, 45000)
                onNavigation(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isCurrent(page, id, token)) navigation++
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!request.isForMainFrame || !isCurrent(page, id, token)) return
                navigation++
                cancelReadTimeout()
                status.text = activity.getString(R.string.http_page_error, response.statusCode.toString(),
                    BrowserPagePolicy.displayHost(request.url.toString()))
            }

            override fun onReceivedError(view: WebView, failed: WebResourceRequest, error: WebResourceError) {
                if (!failed.isForMainFrame || !isCurrent(page, id, token)) return
                val current = request!!
                navigation++
                cancelReadTimeout()
                val phase = when (current.mode) {
                    "auth" -> R.string.phase_login
                    "discover" -> R.string.phase_discover
                    "syncFab" -> R.string.phase_library
                    "claim" -> R.string.phase_checkout
                    "check" -> R.string.phase_ownership
                    else -> R.string.phase_browse
                }
                status.text = activity.getString(R.string.page_load_error, activity.getString(phase),
                    BrowserPagePolicy.displayHost(failed.url.toString()),
                    BrowserPagePolicy.networkCode(error.description.toString(), error.errorCode))
                if (!current.autoRetryAllowed || !BrowserPagePolicy.shouldRetry(current.mode, error.errorCode, retried)) return
                retried = true
                status.append(activity.getString(R.string.read_retry_once))
                handler.postDelayed({ if (isCurrent(page, id, token)) onRetry() }, 1500)
            }
        }
        WebViewCompat.addWebMessageListener(page, "PocketStore", StoreMessagePolicy.origins) { _, message, origin, main, reply ->
            if (!isCurrent(page, id, token) || origin.toString() !in StoreMessagePolicy.origins) return@addWebMessageListener
            val payload = try {
                parseWebMessage(message)
            } catch (_: JSONException) {
                Log.w("PocketBridge", "Ignored malformed store message")
                return@addWebMessageListener
            }
            if (payload.optString("nonce") != id) return@addWebMessageListener
            onMessage(payload, origin.toString(), main) { response ->
                if (isCurrent(page, id, token)) reply.postMessage(response.toString())
            }
        }
        return page
    }

    private fun isCurrent(page: WebView, id: String, token: Long): Boolean =
        !closed && !clearingSession && generation == token && webView === page && request?.id == id

    private fun installScript(page: WebView, source: String?) {
        scriptHandler?.remove()
        scriptHandler = source?.let { WebViewCompat.addDocumentStartJavaScript(page, it, StoreMessagePolicy.origins) }
    }

    private fun updateTitle(current: BrowserRequest) {
        title.text = activity.getString(R.string.task_status, current.position.toString(), current.total.toString(), current.title)
        retry.setText(when (current.mode) {
            "auth" -> R.string.retry_login
            "discover", "syncFab" -> R.string.retry_read
            else -> R.string.verify_result
        })
    }

    private fun disposePage(clearCache: Boolean) {
        generation++
        navigation++
        request = null
        handler.removeCallbacksAndMessages(null)
        readTimeout = null
        progressText = ""
        readOverdue = false
        diagnosticDialog?.dismiss()
        diagnosticDialog = null
        scriptHandler?.remove()
        scriptHandler = null
        val old = webView ?: return
        webView = null
        old.stopLoading()
        panel.removeView(old)
        if (clearCache) {
            old.clearHistory()
            old.clearCache(true)
        }
        old.destroy()
    }

    private fun cancelReadTimeout() {
        readTimeout?.let(handler::removeCallbacks)
        readTimeout = null
        readOverdue = false
    }

    private fun diagnose() {
        if (closed || clearingSession) return
        diagnosticDialog?.dismiss()
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.diagnostics_title)
            .setMessage(R.string.diagnostics_loading).setPositiveButton(R.string.close, null).show()
        diagnosticDialog = dialog
        val token = generation
        network.execute {
            val summary = StringBuilder(activity.getString(R.string.diagnostics_intro))
            for (endpoint in listOf("https://www.fab.com/login", "https://www.epicgames.com/id/login",
                "https://www.epicgames.com/payment/")) {
                var connection: HttpURLConnection? = null
                val host = BrowserPagePolicy.displayHost(endpoint)
                try {
                    connection = URL(endpoint).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "HEAD"
                    connection.useCaches = false
                    summary.append("\n").append(host).append(": HTTP ").append(connection.responseCode)
                } catch (error: IOException) {
                    summary.append("\n").append(host).append(": ").append(error.javaClass.simpleName)
                } finally {
                    connection?.disconnect()
                }
            }
            summary.append(activity.getString(R.string.diagnostics_footer))
            handler.post {
                if (!closed && generation == token && !activity.isDestroyed && dialog.isShowing)
                    dialog.setMessage(summary.toString())
            }
        }
    }

    private fun addButton(row: LinearLayout, label: Int, action: () -> Unit): Button {
        val button = Button(activity)
        button.setText(label)
        button.textSize = 13f
        button.isAllCaps = false
        button.setOnClickListener { if (!closed && !clearingSession) action() }
        row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        return button
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
