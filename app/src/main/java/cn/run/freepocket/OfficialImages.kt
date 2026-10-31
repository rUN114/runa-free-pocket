package cn.run.freepocket

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Arrays
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Public artwork only: no account cookies, tokens, or arbitrary remote URLs. */
internal class OfficialImages(private val directory: File, private val downloader: Downloader = Downloader { url -> download(url) }) {
    internal fun interface Downloader {
        @Throws(IOException::class)
        fun fetch(url: String): ByteArray
    }

    internal fun interface ConnectionOpener {
        @Throws(IOException::class)
        fun open(url: String): HttpURLConnection
    }

    private val sources: MutableMap<String, String> = ConcurrentHashMap()
    private val locks: MutableMap<String, Any> = ConcurrentHashMap()

    fun register(url: String, cover: Boolean): String {
        if (!allowed(url)) return ""
        val key = digest(url + (if (cover) ":cover" else ":preview"))
        sources[key] = url
        return "https://appassets.androidplatform.net/images/" + key + (if (cover) "?cover=1" else "")
    }

    @Throws(IOException::class)
    fun read(key: String, cover: Boolean): ByteArray {
        val source = sources[key]
        if (source == null || key != digest(source + (if (cover) ":cover" else ":preview"))) throw IOException("Unknown image")
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            val cached = File(directory, key)
            if (cached.isFile && cached.length() <= MAX_IMAGE_BYTES) {
                val bytes = Files.readAllBytes(cached.toPath())
                if (mime(bytes) != null) {
                    cached.setLastModified(System.currentTimeMillis())
                    return bytes
                }
                Files.delete(cached.toPath())
            }
            val resized = thumbnail(source, cover)
            val bytes = try {
                fetchImage(resized)
            } catch (thumbnailFailure: IOException) {
                if (resized == source) throw thumbnailFailure
                // Some CDN paths cannot be resized. Retry that exact original artwork once.
                fetchImage(source)
            }
            Files.createDirectories(directory.toPath())
            val temporary = File.createTempFile("image-", ".tmp", directory)
            try {
                Files.write(temporary.toPath(), bytes)
                Files.move(temporary.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
            trimCache()
            return bytes
        }
    }

    @Throws(IOException::class)
    private fun fetchImage(url: String): ByteArray {
        val bytes = downloader.fetch(url)
        if (bytes.size > MAX_IMAGE_BYTES || mime(bytes) == null) throw IOException("Response is not a supported image")
        return bytes
    }

    @Synchronized
    @Throws(IOException::class)
    private fun trimCache() {
        val files = directory.listFiles { file -> file.name.matches("[a-f0-9]{64}".toRegex()) }
            ?: throw IOException("Image cache cannot be read")
        Arrays.sort(files, Comparator.comparingLong { file: File -> file.lastModified() })
        var total = 0L
        for (file in files) total += file.length()
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 80L * 1024 * 1024

        // Observed HTTPS Location returned by cdn1.epicgames.com on the Android network in China.
        private const val EPIC_CHINA_IMAGE_HOST = "cdn1-epicgames-1251447533.file.myqcloud.com"
        private const val MAX_REDIRECTS = 4

        @JvmStatic
        fun allowed(value: String?): Boolean {
            if (value == null) return false
            return try {
                val uri = URI.create(value)
                if ("https" != uri.scheme || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
                val host = uri.host ?: return false
                if (host == EPIC_CHINA_IMAGE_HOST) return true
                for (domain in arrayOf("epicgames.com", "unrealengine.com", "fab.com", "sketchfab.com"))
                    if (host == domain || host.endsWith(".$domain")) return true
                false
            } catch (error: IllegalArgumentException) {
                false
            }
        }

        @JvmStatic
        fun thumbnail(source: String, cover: Boolean): String {
            val uri = URI.create(source)
            if ("cdn1.epicgames.com" != uri.host || uri.rawQuery != null) return source
            val query = if (cover) "?resize=1&w=720&h=405&quality=medium" else "?resize=1&w=960&quality=medium"
            val fragment = source.indexOf('#')
            return (if (fragment < 0) source else source.substring(0, fragment)) + query
        }

        @JvmStatic
        @Throws(IOException::class)
        fun download(source: String): ByteArray =
            download(source) { url -> URI.create(url).toURL().openConnection() as HttpURLConnection }

        @JvmStatic
        @Throws(IOException::class)
        fun download(source: String, connections: ConnectionOpener): ByteArray {
            var current = source
            val visited = HashSet<String>()
            for (redirects in 0..MAX_REDIRECTS) {
                if (!allowed(current)) throw IOException("图片地址或跳转目标不属于受支持的官方 HTTPS 图片节点")
                if (!visited.add(current)) throw IOException("图片服务器发生循环跳转")
                val connection = connections.open(current)
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "image/webp,image/png,image/jpeg,image/gif")
                try {
                    val status = connection.responseCode
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        if (redirects == MAX_REDIRECTS) throw IOException("图片服务器跳转次数过多")
                        val location = connection.getHeaderField("Location")
                        if (location == null || location.trim().isEmpty()) throw IOException("图片服务器跳转地址缺失")
                        try {
                            current = URI.create(current).resolve(location).toString()
                        } catch (error: IllegalArgumentException) {
                            throw IOException("图片服务器跳转地址无效", error)
                        }
                        continue
                    }
                    if (status != 200) throw IOException("图片请求失败 HTTP $status")
                    if (connection.contentLengthLong > MAX_IMAGE_BYTES) throw IOException("图片超过大小限制")
                    connection.inputStream.use { input ->
                        ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                if (output.size() + count > MAX_IMAGE_BYTES) throw IOException("图片超过大小限制")
                                output.write(buffer, 0, count)
                            }
                            return output.toByteArray()
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("图片服务器跳转次数过多")
        }

        @JvmStatic
        fun mime(bytes: ByteArray): String? {
            if (bytes.size < 12) return null
            if ((bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 255) == 216 && (bytes[2].toInt() and 255) == 255) return "image/jpeg"
            if (Arrays.equals(Arrays.copyOf(bytes, 8), byteArrayOf((137).toByte(), 80, 78, 71, 13, 10, 26, 10))) return "image/png"
            if (String(bytes, 0, 6, StandardCharsets.US_ASCII).matches("GIF8[79]a".toRegex())) return "image/gif"
            if (String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP") return "image/webp"
            return null
        }

        private fun digest(value: String): String {
            val bytes = try {
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            } catch (error: java.security.NoSuchAlgorithmException) {
                throw IllegalStateException(error)
            }
            val result = StringBuilder()
            for (byte in bytes) result.append(String.format(Locale.ROOT, "%02x", byte))
            return result.toString()
        }
    }
}
