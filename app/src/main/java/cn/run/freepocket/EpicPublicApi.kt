package cn.run.freepocket

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal object EpicPublicApi {
    @JvmStatic
    @Throws(IOException::class)
    fun promotions(country: String): String {
        // The public feed uses native HTTP so it does not depend on storefront CORS policy.
        val url = URL("https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions" +
            "?locale=zh-CN&country=$country&allowCountries=$country")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 25000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            if (status != 200) throw IOException("Epic 活动接口 HTTP $status")
            connection.inputStream.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > 2 * 1024 * 1024) throw IOException("活动接口响应超出预期大小")
                        output.write(buffer, 0, count)
                    }
                    return output.toString(StandardCharsets.UTF_8.name())
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
