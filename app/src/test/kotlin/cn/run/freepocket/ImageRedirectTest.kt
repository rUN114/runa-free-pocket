package cn.run.freepocket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList

class ImageRedirectTest {
    private val jpeg = byteArrayOf((255).toByte(), (216).toByte(), (255).toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private class Response(url: String, val status: Int, val location: String?, val body: ByteArray) :
        HttpURLConnection(URL(url)) {
        var disconnected = false
        var bodyRead = false
        var declaredLength = body.size.toLong()

        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = if ("Location" == name) location else null
        override fun getContentLengthLong(): Long = declaredLength
        override fun getInputStream(): InputStream {
            bodyRead = true
            return ByteArrayInputStream(body)
        }
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy(): Boolean = false
        override fun connect() {}
    }

    @Test
    fun epicChinaRedirectDownloadsTheRealImageAndClosesBothConnections() {
        for (status in intArrayOf(301, 302, 303, 307, 308)) {
            val requests = ArrayList<String>()
            val redirect = Response(ORIGINAL, status, CHINA, byteArrayOf())
            val image = Response(CHINA, 200, null, jpeg)
            val bytes = OfficialImages.download(ORIGINAL) { url ->
                requests.add(url)
                if (url == ORIGINAL) redirect else image
            }
            assertArrayEquals(jpeg, bytes)
            assertEquals(listOf(ORIGINAL, CHINA), requests)
            assertTrue(redirect.disconnected)
            assertTrue(image.disconnected)
            assertFalse(redirect.bodyRead)
            assertFalse(redirect.instanceFollowRedirects)
            assertNull(image.getRequestProperty("Authorization"))
            assertNull(image.getRequestProperty("Cookie"))
        }
    }

    @Test
    fun relativeRedirectPreservesItsQuery() {
        val requests = ArrayList<String>()
        OfficialImages.download(ORIGINAL) { url ->
            requests.add(url)
            if (url == ORIGINAL) Response(url, 302, "/real.jpg?signature=example", byteArrayOf())
            else Response(url, 200, null, jpeg)
        }
        assertEquals("https://cdn1.epicgames.com/real.jpg?signature=example", requests[1])
    }

    @Test
    fun aRedirectCannotEscapeToAnUntrustedHostOrPlainHttp() {
        for (target in listOf("http://cdn1.epicgames.com/a.jpg", "https://other.file.myqcloud.com/a.jpg",
                "https://cdn1.epicgames.com.evil.test/a.jpg", "https://cdn1-epicgames-1251447533.file.myqcloud.com.evil.test/a.jpg")) {
            val requests = ArrayList<String>()
            assertThrows(IOException::class.java) {
                OfficialImages.download(ORIGINAL) { url ->
                    requests.add(url)
                    Response(url, 302, target, byteArrayOf())
                }
            }
            assertEquals(listOf(ORIGINAL), requests)
        }
    }

    @Test
    fun missingMalformedLoopingAndExcessiveRedirectsFail() {
        for (target in arrayOf<String?>(null, "", "https://bad host/a.jpg", ORIGINAL)) {
            val connections = ArrayList<Response>()
            assertThrows(IOException::class.java) {
                OfficialImages.download(ORIGINAL) { url ->
                    val response = Response(url, 302, target, byteArrayOf())
                    connections.add(response)
                    response
                }
            }
            assertEquals(1, connections.size)
            assertTrue(connections[0].disconnected)
        }
        val chain = ArrayList<Response>()
        assertThrows(IOException::class.java) {
            OfficialImages.download(ORIGINAL) { url ->
                val response = Response(url, 302, "/next-" + chain.size, byteArrayOf())
                chain.add(response)
                response
            }
        }
        assertEquals(5, chain.size)
        assertTrue(chain.all { it.disconnected })
    }

    @Test
    fun finalHttpErrorsAndOversizedImagesStillFailAfterRedirect() {
        for (status in intArrayOf(403, 404, 500)) {
            assertThrows(IOException::class.java) {
                OfficialImages.download(ORIGINAL) { url ->
                    if (url == ORIGINAL) Response(url, 302, CHINA, byteArrayOf())
                    else Response(url, status, null, byteArrayOf())
                }
            }
        }
        val large = Response(CHINA, 200, null, jpeg)
        large.declaredLength = OfficialImages.MAX_IMAGE_BYTES + 1L
        assertThrows(IOException::class.java) {
            OfficialImages.download(ORIGINAL) { url ->
                if (url == ORIGINAL) Response(url, 302, CHINA, byteArrayOf())
                else large
            }
        }
        assertFalse(large.bodyRead)
        assertTrue(large.disconnected)
    }

    companion object {
        private const val ORIGINAL = "https://cdn1.epicgames.com/artwork.jpg"
        private const val CHINA = "https://cdn1-epicgames-1251447533.file.myqcloud.com:443/artwork.jpg"
    }
}
