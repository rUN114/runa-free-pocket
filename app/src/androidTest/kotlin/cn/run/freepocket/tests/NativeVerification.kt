package cn.run.freepocket.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cn.run.freepocket.R
import cn.run.freepocket.model.*
import cn.run.freepocket.ui.*
import cn.run.freepocket.web.BrowserRequest
import cn.run.freepocket.web.OfficialBrowserController
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Diagnostic device tests. Release startup is verified separately through platform UI automation. */
class NativeVerification : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var output: File
    private var activity: Activity? = null
    override fun onCreate(arguments: Bundle) { args = arguments; start() }
    override fun onStart() {
        val result = Bundle()
        try {
            output = File(targetContext.getExternalFilesDir(null), "native-verification").apply {
                check(isDirectory || mkdirs()) { "Cannot create private test output" }
            }
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "Device fixtures require the diagnostic debug APK"
            }
            when (args.getString("mode", "inspect")) {
                "snapshot" -> snapshot()
                "inspect" -> inspect()
                "fixture" -> fixture()
                "browserFixture" -> browserFixture()
                "timeoutFixture" -> timeoutFixture()
                else -> error("Unknown mode")
            }
            result.putString("verification", "passed: " + args.getString("mode", "inspect"))
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            if (::output.isInitialized) write("failure.txt", error.stackTraceToString())
            result.putString("verification", "failed: " + error.javaClass.simpleName + ": " + error.message)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun launch(fixture: Boolean): Activity {
        if (fixture) check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Synthetic fixtures require the diagnostic debug APK"
        }
        return startActivitySync(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(
            "cn.run.freepocket", if (fixture) "cn.run.freepocket.ui.NativeFixtureActivity" else "cn.run.freepocket.MainActivity"
        )).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { host ->
            activity = host
            onMain { host.requestedOrientation = if (args.getString("orientation") == "landscape")
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitForLayout()
        }
    }

    private fun snapshot() {
        val prefs = targetContext.getSharedPreferences("claim-journal", 0)
        val serialized = prefs.getString("data", "{}")!!
        val journal = JSONObject(serialized)
        val items = JSONArray(targetContext.getSharedPreferences("catalogue", 0).getString("items", "[]")!!)
        val counts = JSONObject()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val key = item.getString("category") + ":" + item.optString("status")
            counts.put(key, counts.optInt(key) + 1)
        }
        // Export only hashes/counts; no names, IDs, cookies or authentication material.
        write("snapshot.json", JSONObject().put("journalSha256", hex(serialized.toByteArray()))
            .put("journalTopLevelKeys", JSONArray(journal.keys().asSequence().toList()))
            .put("items", counts).put("snapshotOnly", true).toString(2))
    }

    private fun inspect() {
        val host = launch(false)
        await(15000, "Native catalogue not attached") { onMain { host.findViewById<View>(R.id.catalog) != null } }
        check(onMain { views(host.window.decorView).none { it is WebView } }) { "Startup catalogue is a WebView" }
        screenshot("release-startup.png")
        write("release-inspect.json", JSONObject().put("nativeCatalogue", true)
            .put("debuggable", targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("ordersRequested", 0).toString())
    }

    private fun fixture() {
        val host = launch(true)
        val now = System.currentTimeMillis()
        val cover = "https://cdn1.epicgames.com/native-verification/fixture.png"
        seedImage(cover)
        val actions = mutableListOf<PocketAction>()
        val items = listOf(
            CatalogItem("pc-owned", Category.PC, "已拥有 · 官方游戏", "https://store.epicgames.com/p/fixture",
                status=Ownership.OWNED, cover=cover, end=now+86400000),
            CatalogItem("pc-free", Category.PC, "可领取示例 · 很长的中文游戏标题 Agent A", "https://store.epicgames.com/p/fixture",
                status=Ownership.UNOWNED, cover=cover, originalPrice="¥40", end=now+86400000, screenshots=listOf(cover)),
            CatalogItem("pc-future", Category.PC, "即将推出的游戏", "https://store.epicgames.com/p/fixture",
                status=Ownership.UNOWNED, availability=Availability.UPCOMING, start=now+86400000, end=now+172800000, cover=cover),
            CatalogItem("android-owned", Category.ANDROID, "手机已拥有游戏", "https://store.epicgames.com/p/fixture",
                status=Ownership.OWNED, cover=cover, end=now+86400000),
            CatalogItem("fab-free", Category.FAB, "限免创作素材", "https://www.fab.com/listings/fixture",
                status=Ownership.UNKNOWN, cover=cover, end=now+86400000)
        )
        var state = PocketUiState(items=items, updated=Category.entries.associateWith { now },
            epic=AccountUi(true,"示例账号",now), fab=AccountUi(true,"示例账号",now),
            history=listOf(ClaimRunUi("fixture",now,"paused","Epic：示例账号",
                listOf(ClaimEntryUi("pc-free","可领取示例",Category.PC,"uncertain","可能提交过订单，只核实")),emptyMap())),
            message="诊断示例 · 不会发送真实订单", now=now)
        lateinit var ui: NativePocketView
        onMain { ui = NativePocketView(host) { actions.add(it) }; host.setContentView(ui.view); ui.render(state) }
        waitForLayout()
        val list = onMain { host.findViewById<RecyclerView>(R.id.catalog) }
        screenshot("fixture-home.png")
        val screens = JSONObject().put("fixtureOnly",true).put("ordersRequested",0)
        for (category in Category.entries) {
            state=state.copy(category=category)
            onMain { ui.render(state); list.scrollToPosition(2) }
            await(10000, "Cached artwork did not decode") {
                onMain { views(ui.view).filterIsInstance<ImageView>().any { it.id==R.id.image && it.drawable!=null } }
            }
            check(onMain { views(ui.view).none { it is WebView } }) { "Native fixture contains WebView" }
            checkBounds(ui.view)
            screenshot("fixture-"+category.wire+".png")
        }
        state=state.copy(category=Category.PC)
        onMain { ui.render(state); list.scrollToPosition(2) }; waitForLayout()
        check(onMain { views(ui.view).filterIsInstance<CheckBox>().filter { it.id==R.id.selected }
            .any { !it.isEnabled } }) { "Owned checkbox is selectable" }
        onMain { host.findViewById<Button>(R.id.claim_all).performClick() }
        check(actions.last()==PocketAction.ClaimAll)
        onMain { list.scrollToPosition(0) }; waitForLayout()
        onMain { host.findViewById<CheckBox>(R.id.select_all).performClick() }
        check(actions.last()==PocketAction.SelectAll(true))
        onMain { host.findViewById<CheckBox>(R.id.hide_owned).performClick() }
        check(actions.last()==PocketAction.HideOwnedChanged(true))
        onMain { list.scrollToPosition(2) }; waitForLayout()
        onMain {
            views(ui.view).filterIsInstance<TextView>().first { it.id==R.id.title && it.text.toString()=="已拥有 · 官方游戏" }.performClick()
        }
        waitForLayout()
        check(onMain { views(ui.view).none { it is WebView } })
        onMain {
            views(ui.view).filterIsInstance<Button>().first { it.text.toString()=="打开商品官网" }.performClick()
        }
        check(actions.last()==PocketAction.OpenOfficial("pc-owned"))
        screenshot("fixture-detail.png")
        onMain { check(ui.handleBack()); list.scrollToPosition(5) }; waitForLayout()
        check(onMain { views(ui.view).filterIsInstance<CheckBox>().any { it.contentDescription.toString().contains("即将推出的游戏") && !it.isEnabled } }) {
            "Upcoming item can be selected"
        }
        onMain { host.findViewById<Button>(R.id.account).performClick() }; waitForLayout()
        check(onMain { views(ui.view).filterIsInstance<TextView>().any { it.text.toString()=="账号与设置" } })
        val switch = onMain { views(ui.view).filterIsInstance<Button>().first { it.text.toString()=="切换账号" } }
        onMain { switch.performClick() }
        check(actions.last()==PocketAction.SwitchAccount(Category.PC))
        onMain { views(ui.view).filterIsInstance<Button>().first { it.text.toString()=="退出账号" }.performClick() }
        check(actions.last()==PocketAction.Logout(Category.PC))
        screenshot("fixture-accounts.png")
        onMain { check(ui.handleBack()); host.findViewById<Button>(R.id.history).performClick() }; waitForLayout()
        check(onMain { views(ui.view).filterIsInstance<TextView>().any { it.text.toString().contains("订单待核实") } })
        screenshot("fixture-history.png")
        onMain { check(ui.handleBack()); ui.render(state.copy(busy=true)) }
        check(onMain { !host.findViewById<Button>(R.id.claim_all).isEnabled })
        write("fixture.json",screens.put("uiActionsChecked",true).put("cachedArtworkDecoded",true)
            .put("widthDp",host.resources.configuration.screenWidthDp).put("fontScale",host.resources.configuration.fontScale).toString(2))
        onMain { ui.close(); host.finish() }
    }

    private fun browserFixture() {
        val host = launch(true)
        var messageCount=0
        val markers=mutableListOf<String>()
        lateinit var browser: OfficialBrowserController
        onMain {
            browser=OfficialBrowserController(host,{ data,_,_,_->
                messageCount++
                markers.add(data.optString("marker"))
            },{}, {}, {},{})
            host.setContentView(browser.view)
            browser.show(BrowserRequest("fixture-nonce","https://store.epicgames.com/p/fixture","本机浏览器诊断",
                Category.PC,"check",1,1,autoRetryAllowed=false))
            val web=views(browser.view).filterIsInstance<WebView>().single()
            web.stopLoading()
            check(web.settings.javaScriptCanOpenWindowsAutomatically)
            web.webViewClient=object: WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val script=if(request.url.path=="/checkout") "" else """
                        <script>
                        PocketStore.postMessage(JSON.stringify({nonce:'wrong',type:'waiting'}));
                        setTimeout(()=>window.open('https://store.epicgames.com/checkout'),150);
                        </script>
                    """
                    return WebResourceResponse("text/html","UTF-8",ByteArrayInputStream("<html><body>本机拦截页面$script</body></html>".toByteArray()))
                }
            }
            web.loadUrl("https://store.epicgames.com/p/intercepted-fixture")
        }
        await(10000,"Scripted checkout window did not open") {
            onMain { views(browser.view).filterIsInstance<WebView>().single().url?.endsWith("/checkout")==true }
        }
        check(messageCount==0) { "Wrong nonce reached task handler" }
        onMain {
            val web=views(browser.view).filterIsInstance<WebView>().single()
            val before=web.url
            browser.show(BrowserRequest("fixture-nonce","https://store.epicgames.com/p/fixture","脚本更新",
                Category.PC,"check",1,1,script="window.fixtureMarker='updated';",autoRetryAllowed=false))
            check(web.url==before) { "Same-task script refresh navigated away from the product" }
            web.webViewClient=object:WebViewClient() {
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                    val child=if(request.url.path=="/new-frame") "" else "<iframe src='/new-frame'></iframe>"
                    val html="""<html><body>$child<script>
                        PocketStore.postMessage(JSON.stringify({nonce:'fixture-nonce',type:'waiting',marker:window.fixtureMarker}));
                    </script></body></html>"""
                    return WebResourceResponse("text/html","UTF-8",ByteArrayInputStream(html.toByteArray()))
                }
            }
            web.loadUrl("https://store.epicgames.com/new-document")
        }
        await(10000,"Updated document-start script missing in a new child frame") {
            onMain { markers.count { it=="updated" }>=2 }
        }
        screenshot("browser-fixture.png")
        onMain { browser.hide(); check(views(browser.view).none { it is WebView }); browser.close(); host.finish() }
        write("browser-fixture.json",JSONObject().put("fixtureOnly",true).put("windowOpenRouted",true)
            .put("staleNonceRejected",true).put("updatedScriptInChildFrame",true).put("ordersRequested",0).toString())
    }

    private fun timeoutFixture() {
        val host=launch(true)
        lateinit var browser:OfficialBrowserController
        onMain {
            browser=OfficialBrowserController(host,{_,_,_,_->},{},{},{},{})
            host.setContentView(browser.view)
            browser.show(BrowserRequest("timeout-fixture","https://store.epicgames.com/empty-fixture","读取诊断",
                Category.ANDROID,"discover",1,1,autoRetryAllowed=false))
            val web=views(browser.view).filterIsInstance<WebView>().single()
            web.stopLoading()
            web.webViewClient=object:WebViewClient() {
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=
                    WebResourceResponse("text/html","UTF-8",ByteArrayInputStream("<html><body>无响应诊断</body></html>".toByteArray()))
            }
            web.loadUrl("https://store.epicgames.com/empty-fixture")
        }
        await(43000,"Silent official-page adapter did not produce read timeout") {
            onMain { views(browser.view).filterIsInstance<TextView>().any { it.text.toString().startsWith("网页读取超时") } }
        }
        write("timeout-fixture.json",JSONObject().put("fixtureOnly",true).put("readTimeoutVisible",true).put("ordersRequested",0).toString())
        onMain { browser.close(); host.finish() }
    }

    private fun seedImage(url:String) {
        val image=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff193252.toInt()) }
        val bytes=ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() }
        image.recycle()
        val dir=File(targetContext.cacheDir,"official-images").apply { check(isDirectory || mkdirs()) }
        for (suffix in listOf(":cover",":preview")) File(dir,hex((url+suffix).toByteArray())).writeBytes(bytes)
    }
    private fun hex(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun views(view:View):List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    private fun checkBounds(root:View) = onMain {
        val bounds=android.graphics.Rect()
        for(view in views(root).filter { it is Button && it.isShown && it.getGlobalVisibleRect(bounds) }) {
            check(view.width>0 && view.height>=48*view.resources.displayMetrics.density-1) { "Undersized action" }
        }
    }
    private fun waitForLayout() { waitForIdleSync(); SystemClock.sleep(350) }
    private fun <T> onMain(block:()->T):T {
        var result:Result<T>?=null
        runOnMainSync { result=runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(timeout:Long, failure:String, condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<until) {
            if(condition()) return
            SystemClock.sleep(200)
        }
        error(failure)
    }
    private fun screenshot(name:String) {
        waitForLayout()
        val bitmap=checkNotNull(uiAutomation.takeScreenshot())
        File(output,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun write(name:String,value:String) { File(output,name).writeText(value) }
}
