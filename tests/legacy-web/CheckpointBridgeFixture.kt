package cn.run.freepocket.tests

import android.app.Activity
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Runs only in the diagnostic variant: an isolated, fully intercepted local page; never an official order. */
internal object CheckpointBridgeFixture {
    @Throws(Exception::class)
    fun run(runner: ReleaseVerification, activity: Activity, allow: Boolean) {
        var fixture: WebView? = null
        val domain = asset(runner, "domain.js")
        val automation = asset(runner, "automation.js")
        val script = domain + "\n" + automation +
            "\nrunPocketTask({nonce:'local-fixture',mode:'claim',category:'pc',item:{key:'local-fixture',url:'https://store.epicgames.com/p/local-fixture'}});"
        var committed = false
        runner.runOnMainSync {
            val view = WebView(activity)
            fixture = view
            view.settings.javaScriptEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse {
                    // Every request is intercepted in this isolated test view. No request reaches Epic.
                    val html = "<!doctype html><body><h1>本机模拟结算，不会产生订单</h1><p>Total \$0.00</p>" +
                        "<button onclick=\"window.fixtureClicks=(window.fixtureClicks||0)+1\">Place Order</button></body>"
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8)))
                }
            }
            WebViewCompat.addWebMessageListener(view, "PocketStore", setOf("https://store.epicgames.com")) { _, message, _, _, reply ->
                try {
                    val data = JSONObject(message.data!!)
                    if (data.optString("type") != "actionReady" || data.optString("nonce") != "local-fixture") return@addWebMessageListener
                    if (!allow) return@addWebMessageListener
                    committed = runner.targetContext.getSharedPreferences("local-bridge-fixture", 0)
                        .edit().putBoolean("checkpoint", true).commit()
                    if (committed)
                        reply.postMessage(JSONObject().put("type", "actionPermit").put("nonce", "local-fixture")
                            .put("stage", data.getString("stage")).toString())
                } catch (error: Exception) {
                    throw IllegalStateException("Fixture bridge failed", error)
                }
            }
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf("https://store.epicgames.com"))
            (activity.window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
            view.loadUrl("https://store.epicgames.com/checkout")
        }
        try {
            runner.waitFor({ "true" == runner.js(fixture!!, "!!document.querySelector('button')") }, 15000, "Local bridge page did not load")
            if (allow)
                runner.waitFor({ "1" == runner.js(fixture!!, "window.fixtureClicks||0") }, 15000,
                    "Diagnostic bridge did not receive the native checkpoint permit")
            SystemClock.sleep(2000)
            val clicks = runner.js(fixture!!, "window.fixtureClicks||0").toInt()
            if (clicks != if (allow) 1 else 0) throw IllegalStateException("Local bridge fixture click count incorrect")
            runner.write("bridge-fixture-$allow.json", JSONObject().put("fixtureOnly", true).put("allRequestsIntercepted", true)
                .put("durableCheckpoint", committed).put("clicks", clicks).put("officialOrders", 0).toString())
        } finally {
            runner.runOnMainSync {
                (fixture!!.parent as ViewGroup).removeView(fixture)
                fixture!!.destroy()
                runner.targetContext.deleteSharedPreferences("local-bridge-fixture")
            }
        }
    }

    @Throws(Exception::class)
    private fun asset(runner: ReleaseVerification, name: String): String {
        runner.targetContext.assets.open(name).use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                bytes.write(buffer, 0, count)
            }
            return bytes.toString("UTF-8")
        }
    }
}
