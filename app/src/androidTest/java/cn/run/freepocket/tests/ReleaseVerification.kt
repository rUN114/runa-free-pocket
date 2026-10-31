package cn.run.freepocket.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owner-signed tests. Live modes observe the non-debuggable release; fixture mode is diagnostic only. */
class ReleaseVerification : Instrumentation() {
    private lateinit var arguments: Bundle
    private var activity: Activity? = null
    private var catalogue: WebView? = null
    private lateinit var output: File
    private var selectedAccountUrl = ""

    override fun onCreate(args: Bundle) {
        arguments = args
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            output = File(getTargetContext().getExternalFilesDir(null), "verification")
            if (!output.isDirectory && !output.mkdirs()) throw IllegalStateException("Cannot create test output")
            val mode = arguments.getString("mode", "inspect")
            if (mode !in listOf("inspect", "savedStateSnapshot", "windowFixture", "batch", "resume", "interrupt", "fixture", "fabCheckoutInspect", "networkErrorInspect"))
                throw IllegalStateException("Unknown verification mode")
            if (mode == "fixture" && getTargetContext().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
                throw IllegalStateException("Fixture mode requires the separate diagnostic build")
            if (mode != "fixture" && getTargetContext().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                throw IllegalStateException("Target is debuggable; this test requires the actual release")
            if (mode == "savedStateSnapshot") {
                val journal = getTargetContext().getSharedPreferences("claim-journal", 0).getString("data", "{}")!!
                val items = getTargetContext().getSharedPreferences("catalogue", 0).getString("items", "[]")!!
                write("saved-state-snapshot.json", JSONObject().put("snapshotOnly", true)
                    .put("capturedAt", System.currentTimeMillis()).put("journal", JSONObject(journal))
                    .put("items", JSONArray(items)).toString(2))
                result.putString("verification", "saved state captured; no new sync, activity launch or claiming requested")
                finish(Activity.RESULT_OK, result); return
            }
            restoreAuthorizedCookies()
            activity = startActivitySync(
                Intent(Intent.ACTION_MAIN).setComponent(ComponentName("cn.run.freepocket", "cn.run.freepocket.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = activity!!
            if (arguments.getString("portrait", "false") == "true")
                runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor({
                runOnMainSync { catalogue = findCatalogue(activity.window.decorView) }
                catalogue != null && "true" == js(catalogue!!, "typeof window.receiveState==='function'&&!state.busy")
            }, 100000, "Initial catalogue did not become ready")
            if (mode == "windowFixture") {
                var testView: WebView? = null
                runOnMainSync {
                    testView = findStore(activity.window.decorView)!!
                    (testView!!.parent as View).visibility = View.VISIBLE
                    testView!!.webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse {
                            val html = if (request.url.path == "/checkout") "<html><body>Local window fixture complete</body></html>"
                                else "<html><body>Local window fixture<script>setTimeout(()=>window.open('https://store.epicgames.com/checkout'),200)</script></body></html>"
                            return android.webkit.WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8)))
                        }
                    }
                    testView!!.loadUrl("https://store.epicgames.com/p/local-window-fixture")
                }
                waitFor({ "true" == js(testView!!, "location.pathname==='/checkout'") }, 10000,
                    "Store WebView blocked the local scripted window")
                result.putString("verification", "passed; local window requests intercepted; no orders")
                finish(Activity.RESULT_OK, result); return
            }
            if (mode == "networkErrorInspect") {
                runOnMainSync {
                    val store = findStore(activity.window.decorView)!!
                    (store.parent as View).visibility = View.VISIBLE
                    store.loadUrl("https://www.fab.com:1/login?state=test_value_must_not_be_in_status")
                }
                var status: String? = null
                waitFor({
                    runOnMainSync { status = findErrorStatus(activity.window.decorView) }
                    status != null
                }, 20000, "Native network error was not shown")
                if (!status!!.contains("www.fab.com") || status!!.contains("test_value_must_not_be_in_status") || status!!.contains("?state="))
                    throw IllegalStateException("Error status missing host or leaking query")
                write("network-error-inspect.json",
                    JSONObject().put("fixtureOnly", true).put("officialOrders", 0).put("nativeStatus", status).toString())
                result.putString("verification", "passed; actual release WebView failure and native redacted error status; no orders")
                finish(Activity.RESULT_OK, result); return
            }
            if (mode == "fabCheckoutInspect") {
                var store: WebView? = null
                runOnMainSync {
                    store = findStore(activity.window.decorView)
                    (store!!.parent as View).visibility = View.VISIBLE
                    store!!.loadUrl("https://www.fab.com/listings/be5ed72e-f542-41f4-b747-bdb343eef609?lang=en")
                }
                waitFor({ "true" == js(store!!, "!!document.querySelector('#license-select')") }, 90000, "Fab product requires login or could not load")
                js(store!!, "document.querySelector('#license-select').click(),true")
                waitFor({ "true" == js(store!!, "[...document.querySelectorAll('[role=option]')].some(e=>/^Professional/i.test(e.innerText))") },
                    10000, "License options missing")
                js(store!!, "[...document.querySelectorAll('[role=option]')].find(e=>/^Professional/i.test(e.innerText)).click(),true")
                waitFor({ "true" == js(store!!, "document.querySelector('#license-select').innerText.toLowerCase().includes('professional')") },
                    10000, "Professional selection not confirmed")
                js(store!!, "[...document.querySelectorAll('button')].find(e=>/^buy now$/i.test(e.innerText.trim())).click(),true")
                SystemClock.sleep(8000)
                write("fab-checkout-dom.json", js(store!!, "JSON.stringify({path:location.pathname," +
                    "frames:[...document.querySelectorAll('iframe')].map(e=>{try{return new URL(e.src).origin+new URL(e.src).pathname}catch{return ''}})," +
                    "controls:[...document.querySelectorAll('button,select,label,[role=dialog],h1,h2,h3')]" +
                    ".map(e=>({tag:e.tagName,id:e.id,testid:e.getAttribute('data-testid'),text:e.innerText}))" +
                    ".filter(e=>/license|personal|professional|total|free|library|order/i.test(e.text)).map(e=>({...e,text:e.text.slice(0,4000)}))})"))
                screenshot("fab-checkout-inspect.png")
                result.putString("verification", "checkout inspection only; no submit button clicked")
                finish(Activity.RESULT_OK, result); return
            }
            if (mode == "inspect") {
                result.putString("verification", "passed; actual release startup; no claiming requested")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (mode == "fixture") {
                CheckpointBridgeFixture.run(this, activity, true)
                CheckpointBridgeFixture.run(this, activity, false)
                result.putString("verification", "passed; local intercepted bridge fixture only; no official orders")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (mode == "interrupt") {
                js(catalogue!!, "document.querySelector('#claim-all').click(), true")
                waitFor({ "true" == js(catalogue!!, "state.busy&&state.history.length>0") }, 5000, "Batch checkpoint missing")
                write("interrupted.json", js(catalogue!!, "JSON.stringify({id:state.history[0].id,state:state.history[0].state})"))
                val checkpoint = Bundle()
                checkpoint.putString("checkpoint", "persisted; intentional process kill follows")
                sendStatus(1, checkpoint)
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
            if (mode == "resume") {
                if ("true" != js(catalogue!!, "state.history[0]?.state==='paused'"))
                    throw IllegalStateException("Interrupted release task was not restored as paused")
                js(catalogue!!, "document.querySelector('#resume-batch').click(), true")
            } else {
                js(catalogue!!, "document.querySelector('#claim-all').click(), true")
            }
            waitFor({
                selectAuthorizedAccountIfShown()
                "true" == js(catalogue!!, "!state.busy&&state.history?.[0]?.state==='done'")
            }, 360000, "Release batch did not finish; may require human verification")
            val snapshot = JSONObject(js(catalogue!!,
                "JSON.stringify({history:state.history,items:state.items,epic:state.epicAccount,fab:state.fabAccount})"))
            val latest = snapshot.getJSONArray("history").getJSONObject(0)
            if (latest.getJSONObject("errors").length() != 0)
                throw IllegalStateException("Release batch contains category errors; inspect private report")
            val entries = latest.getJSONArray("entries")
            if (entries.length() == 0) throw IllegalStateException("Release produced no inventory results")
            for (i in 0 until entries.length()) {
                val status = entries.getJSONObject(i).getString("status")
                if (status != "owned" && status != "skipped" && status != "claimed")
                    throw IllegalStateException("Unfinished release item: $status")
            }
            write("$mode-real-account.json", snapshot.toString(2))
            for (category in listOf("pc", "android", "fab")) {
                js(catalogue!!, "document.querySelector('[data-tab=" + category + "]').click(), true")
                waitFor({ "true" == js(catalogue!!, "!state.busy&&[...document.querySelectorAll('.cover-button img')].length>0" +
                    "&&[...document.querySelectorAll('.cover-button img')].every(i=>i.complete&&i.naturalWidth>0)") },
                    90000, "Official images failed to decode")
                write("$mode-$category-layout.json", js(catalogue!!,
                    "JSON.stringify({width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth," +
                        "images:[...document.querySelectorAll('.cover-button img')].map(i=>({decoded:i.complete&&i.naturalWidth>0,width:i.naturalWidth,height:i.naturalHeight}))})"))
                screenshot("$mode-$category.png")
            }
            js(catalogue!!, "document.querySelector('#show-history').click(), true")
            screenshot("$mode-history.png")
            result.putString("verification", "passed; actual non-debuggable release and official inventory; private evidence saved")
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            try {
                if (catalogue != null)
                    write("failure-state.json", js(catalogue!!,
                        "JSON.stringify({history:state.history,message:state.message,busy:state.busy})"))
            } catch (ignored: Exception) {
                /* The primary failure remains the instrumentation result. */
            }
            result.putString("verification", error.javaClass.simpleName + ": " + error.message)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun findCatalogue(view: View): WebView? {
        if (view is WebView && view.url != null && view.url!!.startsWith("https://appassets.androidplatform.net/")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findCatalogue(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    private fun findErrorStatus(view: View): String? {
        if (view is android.widget.TextView) {
            val text = view.text.toString()
            if (text.startsWith("页面加载失败")) return text
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val text = findErrorStatus(view.getChildAt(i))
            if (text != null) return text
        }
        return null
    }

    private fun findStore(view: View): WebView? {
        if (view is WebView && view !== catalogue) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findStore(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    @Throws(Exception::class)
    fun js(webView: WebView, expression: String): String {
        var raw: String? = null
        val latch = CountDownLatch(1)
        runOnMainSync {
            webView.evaluateJavascript("(()=>{" + "return (" + expression + ");})()") { value ->
                raw = value
                latch.countDown()
            }
        }
        if (!latch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("WebView evaluation timed out")
        val decoded = JSONTokener(raw).nextValue()
        return if (decoded == JSONObject.NULL) "null" else decoded.toString()
    }

    @Throws(Exception::class)
    private fun selectAuthorizedAccountIfShown() {
        val accountFile = File(getTargetContext().getExternalFilesDir(null), "account.json")
        if (!accountFile.isFile) return
        val email = JSONObject(String(Files.readAllBytes(accountFile.toPath()), StandardCharsets.UTF_8)).getString("email")
        var choice: WebView? = null
        runOnMainSync { choice = findAccountChooser(activity!!.window.decorView) }
        if (choice == null) return
        var currentUrl: String? = null
        runOnMainSync { currentUrl = choice!!.url }
        if (currentUrl == selectedAccountUrl) return
        val position = js(choice!!, "(()=>{const email=" + JSONObject.quote(email) +
            ";const choices=[...document.querySelectorAll('p,span,div,button,a')]" +
            ".filter(el=>el.children.length===0&&el.textContent.trim()===email" +
            "&&el.getBoundingClientRect().width>0&&el.getBoundingClientRect().height>0);" +
            "if(choices.length!==1)return null;const r=choices[0].getBoundingClientRect();" +
            "return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,width:innerWidth});})()")
        if (position == "null") return
        val point = JSONObject(position)
        val location = IntArray(2)
        var width = 0
        runOnMainSync {
            choice!!.getLocationOnScreen(location)
            width = choice!!.width
        }
        val scale = (width / point.getDouble("width")).toFloat()
        val x = location[0] + point.getDouble("x").toFloat() * scale
        val y = location[1] + point.getDouble("y").toFloat() * scale
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 100, MotionEvent.ACTION_UP, x, y, 0)
        try {
            sendPointerSync(down)
            sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        selectedAccountUrl = currentUrl!!
    }

    private fun findAccountChooser(view: View): WebView? {
        if (view is WebView) {
            val url = view.url
            if (url != null && url.startsWith("https://www.epicgames.com/id/login/switch-account")) return view
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val found = findAccountChooser(view.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    @Throws(Exception::class)
    private fun restoreAuthorizedCookies() {
        val file = File(getTargetContext().getExternalFilesDir(null), "cookies.json")
        if (!file.isFile) return
        val cookies = JSONArray(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        val latch = CountDownLatch(1)
        runOnMainSync {
            try {
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                for (i in 0 until cookies.length()) {
                    val cookie = cookies.getJSONObject(i)
                    val domain = cookie.getString("domain")
                    if (domain != "epicgames.com" && !domain.endsWith(".epicgames.com") &&
                        domain != "fab.com" && !domain.endsWith(".fab.com"))
                        throw IllegalStateException("Unexpected cookie domain")
                    var header = cookie.getString("name") + "=" + cookie.getString("value") +
                        "; Domain=" + domain + "; Path=" + cookie.getString("path")
                    if (cookie.optBoolean("secure")) header += "; Secure"
                    if (cookie.optBoolean("httpOnly")) header += "; HttpOnly"
                    if (cookie.has("sameSite")) header += "; SameSite=" + cookie.getString("sameSite")
                    manager.setCookie("https://" + domain.replaceFirst("^\\.".toRegex(), "") + cookie.getString("path"), header)
                }
                manager.flush()
            } catch (error: Exception) {
                throw IllegalStateException("Cannot restore authorized local session", error)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("Cookie restore timed out")
        Files.delete(file.toPath())
    }

    fun interface Condition {
        fun ready(): Boolean
    }

    @Throws(Exception::class)
    fun waitFor(condition: Condition, timeout: Long, failure: String) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (condition.ready()) return
            SystemClock.sleep(800)
        }
        throw IllegalStateException(failure)
    }

    @Throws(Exception::class)
    fun write(name: String, text: String) {
        Files.write(File(output, name).toPath(), text.toByteArray(StandardCharsets.UTF_8))
    }

    @Throws(Exception::class)
    private fun screenshot(name: String) {
        val painted = CountDownLatch(1)
        runOnMainSync {
            catalogue!!.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    painted.countDown()
                }
            })
        }
        if (!painted.await(10, TimeUnit.SECONDS)) throw IllegalStateException("Release visual frame timed out")
        SystemClock.sleep(200)
        val bitmap = uiAutomation.takeScreenshot()
            ?: throw IllegalStateException("Cannot capture release screenshot")
        FileOutputStream(File(output, name)).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        bitmap.recycle()
    }
}
