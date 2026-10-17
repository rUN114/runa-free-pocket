package cn.run.freepocket;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ImageRedirectTest {
    private static final String ORIGINAL = "https://cdn1.epicgames.com/artwork.jpg";
    private static final String CHINA = "https://cdn1-epicgames-1251447533.file.myqcloud.com:443/artwork.jpg";
    private final byte[] jpeg = new byte[]{(byte)255,(byte)216,(byte)255,0,0,0,0,0,0,0,0,0};

    private static final class Response extends HttpURLConnection {
        final int status;
        final String location;
        final byte[] body;
        boolean disconnected;
        boolean bodyRead;
        long declaredLength;
        Response(String url, int status, String location, byte[] body) throws IOException {
            super(new URL(url));
            this.status = status;
            this.location = location;
            this.body = body;
            this.declaredLength = body.length;
        }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public long getContentLengthLong() { return declaredLength; }
        @Override public InputStream getInputStream() { bodyRead = true; return new ByteArrayInputStream(body); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    @Test public void epicChinaRedirectDownloadsTheRealImageAndClosesBothConnections() throws Exception {
        for (int status : new int[]{301,302,303,307,308}) {
            List<String> requests = new ArrayList<>();
            Response redirect = new Response(ORIGINAL, status, CHINA, new byte[0]);
            Response image = new Response(CHINA, 200, null, jpeg);
            byte[] bytes = OfficialImages.download(ORIGINAL, url -> {
                requests.add(url);
                return url.equals(ORIGINAL) ? redirect : image;
            });
            assertArrayEquals(jpeg, bytes);
            assertEquals(List.of(ORIGINAL, CHINA), requests);
            assertTrue(redirect.disconnected);
            assertTrue(image.disconnected);
            assertFalse(redirect.bodyRead);
            assertFalse(redirect.getInstanceFollowRedirects());
            assertNull(image.getRequestProperty("Authorization"));
            assertNull(image.getRequestProperty("Cookie"));
        }
    }

    @Test public void relativeRedirectPreservesItsQuery() throws Exception {
        List<String> requests = new ArrayList<>();
        OfficialImages.download(ORIGINAL, url -> {
            requests.add(url);
            if (url.equals(ORIGINAL)) return new Response(url, 302, "/real.jpg?signature=example", new byte[0]);
            return new Response(url, 200, null, jpeg);
        });
        assertEquals("https://cdn1.epicgames.com/real.jpg?signature=example", requests.get(1));
    }

    @Test public void aRedirectCannotEscapeToAnUntrustedHostOrPlainHttp() throws Exception {
        for (String target : List.of("http://cdn1.epicgames.com/a.jpg", "https://other.file.myqcloud.com/a.jpg",
                "https://cdn1.epicgames.com.evil.test/a.jpg", "https://cdn1-epicgames-1251447533.file.myqcloud.com.evil.test/a.jpg")) {
            List<String> requests = new ArrayList<>();
            assertThrows(IOException.class, () -> OfficialImages.download(ORIGINAL, url -> {
                requests.add(url);
                return new Response(url, 302, target, new byte[0]);
            }));
            assertEquals(List.of(ORIGINAL), requests);
        }
    }

    @Test public void missingMalformedLoopingAndExcessiveRedirectsFail() throws Exception {
        for (String target : new String[]{null,"","https://bad host/a.jpg",ORIGINAL}) {
            List<Response> connections = new ArrayList<>();
            assertThrows(IOException.class, () -> OfficialImages.download(ORIGINAL, url -> {
                Response response = new Response(url, 302, target, new byte[0]);
                connections.add(response);
                return response;
            }));
            assertEquals(1, connections.size());
            assertTrue(connections.get(0).disconnected);
        }
        List<Response> chain = new ArrayList<>();
        assertThrows(IOException.class, () -> OfficialImages.download(ORIGINAL, url -> {
            Response response = new Response(url, 302, "/next-" + chain.size(), new byte[0]);
            chain.add(response);
            return response;
        }));
        assertEquals(5, chain.size());
        assertTrue(chain.stream().allMatch(response -> response.disconnected));
    }

    @Test public void finalHttpErrorsAndOversizedImagesStillFailAfterRedirect() throws Exception {
        for (int status : new int[]{403,404,500}) {
            assertThrows(IOException.class, () -> OfficialImages.download(ORIGINAL, url -> {
                if (url.equals(ORIGINAL)) return new Response(url, 302, CHINA, new byte[0]);
                return new Response(url, status, null, new byte[0]);
            }));
        }
        Response large = new Response(CHINA, 200, null, jpeg);
        large.declaredLength = OfficialImages.MAX_IMAGE_BYTES + 1L;
        assertThrows(IOException.class, () -> OfficialImages.download(ORIGINAL, url -> {
            if (url.equals(ORIGINAL)) return new Response(url, 302, CHINA, new byte[0]);
            return large;
        }));
        assertFalse(large.bodyRead);
        assertTrue(large.disconnected);
    }
}
