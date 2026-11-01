package cn.run.freepocket.web

import cn.run.freepocket.StoreMessagePolicy
import java.net.URI
import java.net.URISyntaxException

internal object BrowserPagePolicy {
    fun canStartRequest(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return "https://$host" in StoreMessagePolicy.origins
    }

    fun canNavigate(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
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
