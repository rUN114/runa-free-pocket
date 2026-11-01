package cn.run.freepocket.web

import java.net.URI
import java.net.URISyntaxException
import cn.run.freepocket.StoreMessagePolicy

internal object BrowserPagePolicy {
    fun canNavigate(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
    }

    fun canStart(url: String): Boolean {
        val uri = try { URI(url) } catch (_: URISyntaxException) { return false }
        return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            "https://" + uri.host in StoreMessagePolicy.origins
    }

    fun displayHost(url: String): String = try {
        URI(url).host.orEmpty()
    } catch (_: URISyntaxException) {
        ""
    }

    fun networkCode(description: String, errorCode: Int): String =
        Regex("net::ERR_[A-Z_]+").find(description)?.value ?: "WebView $errorCode"

    fun shouldRetry(mode: String, errorCode: Int, retried: Boolean): Boolean =
        !retried && mode in setOf("auth", "discover", "syncFab") && errorCode in setOf(-6, -8)
}
