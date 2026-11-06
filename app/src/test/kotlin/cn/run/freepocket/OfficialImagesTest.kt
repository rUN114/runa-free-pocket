package cn.run.freepocket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class OfficialImagesTest {
    private val jpeg = byteArrayOf((255).toByte(), (216).toByte(), (255).toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private fun key(url: String): String = URI.create(url).path.substring("/images/".length)

    @Test
    fun onlyRegisteredOfficialHttpsArtworkCanBeLoaded() {
        val images = OfficialImages(Files.createTempDirectory("images").toFile()) {
            throw AssertionError("No download allowed")
        }
        for (url in listOf("http://cdn1.epicgames.com/a.jpg", "https://cdn1.epicgames.com.evil.test/a.jpg",
                "https://user:pass@media.fab.com/a.jpg", "https://media.fab.com:9443/a.jpg", "javascript:alert(1)"))
            assertEquals("", images.register(url, true))
        assertThrows(IOException::class.java) { images.read("../private", true) }
        assertThrows(IOException::class.java) { images.read("a".repeat(64), true) }
    }

    @Test
    fun aSuccessfulDownloadIsReusedFromDiskEvenAfterReopening() {
        val directory = Files.createTempDirectory("images").toFile()
        val requests = ArrayList<String>()
        val images = OfficialImages(directory) { url ->
            requests.add(url)
            jpeg
        }
        val local = images.register("https://cdn1.epicgames.com/a.jpg", true)
        assertArrayEquals(jpeg, images.read(key(local), true))
        assertArrayEquals(jpeg, images.read(key(local), true))
        assertEquals(1, requests.size)
        assertTrue(requests[0].contains("w=720&h=405"))
        val reopened = OfficialImages(directory) {
            throw AssertionError("Cache should work without network")
        }
        reopened.register("https://cdn1.epicgames.com/a.jpg", true)
        assertArrayEquals(jpeg, reopened.read(key(local), true))
    }

    @Test
    fun blockedOrHtmlThumbnailFallsBackToThatExactOriginal() {
        for (httpError in booleanArrayOf(true, false)) {
            val requests = ArrayList<String>()
            val images = OfficialImages(Files.createTempDirectory("images").toFile()) { url ->
                requests.add(url)
                if (url.contains("?")) {
                    if (httpError) throw IOException("HTTP 403")
                    "<html>Security challenge</html>".toByteArray(StandardCharsets.UTF_8)
                } else jpeg
            }
            val local = images.register("https://cdn1.epicgames.com/exact-original.jpg", false)
            assertArrayEquals(jpeg, images.read(key(local), false))
            assertEquals(listOf("https://cdn1.epicgames.com/exact-original.jpg?resize=1&w=960&quality=medium",
                "https://cdn1.epicgames.com/exact-original.jpg"), requests)
        }
    }

    @Test
    fun unreadableArtworkDoesNotBecomeACachedFakeImage() {
        val directory = Files.createTempDirectory("images").toFile()
        val images = OfficialImages(directory) {
            "<html>not a cover</html>".toByteArray(StandardCharsets.UTF_8)
        }
        val local = images.register("https://media.fab.com/a.jpg?signature=x", true)
        assertThrows(IOException::class.java) { images.read(key(local), true) }
        assertFalse(File(directory, key(local)).exists())
        assertNull(OfficialImages.mime(byteArrayOf()))
    }

    @Test
    fun signedAndFabImagesAreNotRewrittenAndMalformedCacheIsReplaced() {
        val source = "https://media.fab.com/a.jpg?signature=example"
        assertEquals(source, OfficialImages.thumbnail(source, true))
        assertEquals("https://cdn1.epicgames.com/a.jpg?signature=x",
            OfficialImages.thumbnail("https://cdn1.epicgames.com/a.jpg?signature=x", false))
        val directory = Files.createTempDirectory("images").toFile()
        val images = OfficialImages(directory) { url ->
            assertEquals(source, url)
            jpeg
        }
        val local = images.register(source, true)
        Files.write(File(directory, key(local)).toPath(), "<html>old failure</html>".toByteArray(StandardCharsets.UTF_8))
        assertArrayEquals(jpeg, images.read(key(local), true))
    }

    @Test
    fun simultaneousFirstDownloadsCanCreateTheSameCacheDirectory() {
        val directory = Files.createTempDirectory("images").resolve("cache").toFile()
        val ready = CountDownLatch(4)
        val release = CountDownLatch(1)
        val images = OfficialImages(directory) { url ->
            ready.countDown()
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw IOException("Test download timed out")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(error)
            }
            jpeg
        }
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = ArrayList<Future<ByteArray>>()
            for (i in 0 until 4) {
                val local = images.register("https://media.fab.com/$i.jpg", true)
                results.add(workers.submit(Callable { images.read(key(local), true) }))
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()
            for (result in results) assertArrayEquals(jpeg, result.get(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
