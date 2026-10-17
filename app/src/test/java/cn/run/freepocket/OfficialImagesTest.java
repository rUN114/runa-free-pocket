package cn.run.freepocket;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class OfficialImagesTest {
    private final byte[] jpeg = new byte[]{(byte)255,(byte)216,(byte)255,0,0,0,0,0,0,0,0,0};
    private String key(String url) { return URI.create(url).getPath().substring("/images/".length()); }
    @Test public void onlyRegisteredOfficialHttpsArtworkCanBeLoaded() throws Exception {
        OfficialImages images = new OfficialImages(Files.createTempDirectory("images").toFile(), url -> { throw new AssertionError("No download allowed"); });
        for (String url : List.of("http://cdn1.epicgames.com/a.jpg", "https://cdn1.epicgames.com.evil.test/a.jpg", "https://user:pass@media.fab.com/a.jpg", "https://media.fab.com:9443/a.jpg", "javascript:alert(1)")) assertEquals("", images.register(url, true));
        assertThrows(IOException.class, () -> images.read("../private", true));
        assertThrows(IOException.class, () -> images.read("a".repeat(64), true));
    }
    @Test public void aSuccessfulDownloadIsReusedFromDiskEvenAfterReopening() throws Exception {
        File directory = Files.createTempDirectory("images").toFile();
        List<String> requests = new ArrayList<>();
        OfficialImages images = new OfficialImages(directory, url -> { requests.add(url); return jpeg; });
        String local = images.register("https://cdn1.epicgames.com/a.jpg", true);
        assertArrayEquals(jpeg, images.read(key(local), true));
        assertArrayEquals(jpeg, images.read(key(local), true));
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).contains("w=720&h=405"));
        OfficialImages reopened = new OfficialImages(directory, url -> { throw new AssertionError("Cache should work without network"); });
        reopened.register("https://cdn1.epicgames.com/a.jpg", true);
        assertArrayEquals(jpeg, reopened.read(key(local), true));
    }
    @Test public void blockedOrHtmlThumbnailFallsBackToThatExactOriginal() throws Exception {
        for (boolean httpError : new boolean[]{true, false}) {
            List<String> requests = new ArrayList<>();
            OfficialImages images = new OfficialImages(Files.createTempDirectory("images").toFile(), url -> {
                requests.add(url);
                if (url.contains("?")) {
                    if (httpError) throw new IOException("HTTP 403");
                    return "<html>Security challenge</html>".getBytes(StandardCharsets.UTF_8);
                }
                return jpeg;
            });
            String local = images.register("https://cdn1.epicgames.com/exact-original.jpg", false);
            assertArrayEquals(jpeg, images.read(key(local), false));
            assertEquals(List.of("https://cdn1.epicgames.com/exact-original.jpg?resize=1&w=960&quality=medium", "https://cdn1.epicgames.com/exact-original.jpg"), requests);
        }
    }
    @Test public void unreadableArtworkDoesNotBecomeACachedFakeImage() throws Exception {
        File directory = Files.createTempDirectory("images").toFile();
        OfficialImages images = new OfficialImages(directory, url -> "<html>not a cover</html>".getBytes(StandardCharsets.UTF_8));
        String local = images.register("https://media.fab.com/a.jpg?signature=x", true);
        assertThrows(IOException.class, () -> images.read(key(local), true));
        assertFalse(new File(directory, key(local)).exists());
        assertNull(OfficialImages.mime(new byte[0]));
    }
    @Test public void signedAndFabImagesAreNotRewrittenAndMalformedCacheIsReplaced() throws Exception {
        String source = "https://media.fab.com/a.jpg?signature=example";
        assertEquals(source, OfficialImages.thumbnail(source, true));
        assertEquals("https://cdn1.epicgames.com/a.jpg?signature=x", OfficialImages.thumbnail("https://cdn1.epicgames.com/a.jpg?signature=x", false));
        File directory = Files.createTempDirectory("images").toFile();
        OfficialImages images = new OfficialImages(directory, url -> { assertEquals(source, url); return jpeg; });
        String local = images.register(source, true);
        Files.write(new File(directory, key(local)).toPath(), "<html>old failure</html>".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(jpeg, images.read(key(local), true));
    }
}
