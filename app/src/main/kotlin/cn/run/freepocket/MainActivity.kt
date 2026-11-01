package cn.run.freepocket

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewFeature
import cn.run.freepocket.ui.NativePocketView
import cn.run.freepocket.ui.PocketViewModel
import cn.run.freepocket.web.OfficialBrowserController
import cn.run.freepocket.web.BrowserRequest
import kotlinx.coroutines.launch

/** Android lifecycle and view wiring; no inventory, account or claim decisions live here. */
class MainActivity : ComponentActivity() {
    private lateinit var model: PocketViewModel
    private lateinit var nativeUi: NativePocketView
    private lateinit var browser: OfficialBrowserController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            AlertDialog.Builder(this).setTitle(R.string.webview_update_title)
                .setMessage(R.string.webview_update_message)
                .setPositiveButton(R.string.close) { _, _ -> finish() }.setCancelable(false).show()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        model = ViewModelProvider(this)[PocketViewModel::class.java]
        nativeUi = NativePocketView(this, model::dispatch)
        browser = OfficialBrowserController(this,
            onMessage = { data, origin, main, reply ->
                model.webMessage(data, origin, main) { permit ->
                    // Fab grants update the script for future checkout frames before replying.
                    val request = model.browser.value
                    if (request != null && showBrowser(request)) reply(permit)
                }
            }, onBack = model::browserBack, onRetry = model::browserRetry,
            onSkip = model::browserSkip, onNavigation = model::navigation)
        val root = FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.native_background))
        root.addView(nativeUi.view, FrameLayout.LayoutParams(-1, -1))
        root.addView(browser.view, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        nativeUi.render(model.state.value)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (model.browser.value != null) model.browserBack()
                else if (!nativeUi.handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.state.collect { state ->
                        nativeUi.render(state)
                        if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                launch { model.browser.collect { request -> if (request == null) browser.hide() else showBrowser(request) } }
                launch { model.browserStatus.collect { if (it.isNotEmpty()) browser.setStatus(it) } }
                launch { model.clearSession.collect { if (it) browser.clearSession(model::sessionCleared) } }
            }
        }
    }

    private fun showBrowser(request: BrowserRequest): Boolean {
        if (browser.show(request)) return true
        model.browserFailed(request.id)
        return false
    }

    override fun onStart() { super.onStart(); if (::model.isInitialized) model.setVisible(true) }
    override fun onStop() { if (::model.isInitialized) model.setVisible(false); super.onStop() }
    override fun onDestroy() {
        if (::browser.isInitialized) browser.close()
        if (::nativeUi.isInitialized) nativeUi.close()
        super.onDestroy()
    }
}
