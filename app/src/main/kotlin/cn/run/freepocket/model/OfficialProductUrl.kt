package cn.run.freepocket.model

import java.net.URI

/** Catalogue entries can only point to their own storefront, including when loaded from an old cache. */
internal object OfficialProductUrl {
    fun accepts(category: Category, value: String): Boolean {
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return false }
        val expectedHost = if (category == Category.FAB) "www.fab.com" else "store.epicgames.com"
        return uri.scheme == "https" && uri.host == expectedHost && uri.userInfo == null && uri.port == -1
    }
}
