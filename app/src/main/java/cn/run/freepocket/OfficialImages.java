package cn.run.freepocket;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Public artwork only: no account cookies, tokens, or arbitrary remote URLs. */
final class OfficialImages {
    static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 80L * 1024 * 1024;
    // Observed HTTPS Location returned by cdn1.epicgames.com on the Android network in China.
    private static final String EPIC_CHINA_IMAGE_HOST = "cdn1-epicgames-1251447533.file.myqcloud.com";
    private static final int MAX_REDIRECTS = 4;
    private final File directory;
    interface Downloader { byte[] fetch(String url) throws IOException; }
    interface ConnectionOpener { HttpURLConnection open(String url) throws IOException; }
    private final Downloader downloader;
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    OfficialImages(File directory) { this(directory, OfficialImages::download); }
    OfficialImages(File directory, Downloader downloader) { this.directory = directory; this.downloader = downloader; }

    String register(String url, boolean cover) {
        if (!allowed(url)) return "";
        String key = digest(url + (cover ? ":cover" : ":preview"));
        sources.put(key, url);
        return "https://appassets.androidplatform.net/images/" + key + (cover ? "?cover=1" : "");
    }

    byte[] read(String key, boolean cover) throws IOException {
        String source = sources.get(key);
        if (source == null || !key.equals(digest(source + (cover ? ":cover" : ":preview")))) throw new IOException("Unknown image");
        synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
            File cached = new File(directory, key);
            if (cached.isFile() && cached.length() <= MAX_IMAGE_BYTES) {
                byte[] bytes = Files.readAllBytes(cached.toPath());
                if (mime(bytes) != null) { cached.setLastModified(System.currentTimeMillis()); return bytes; }
                Files.delete(cached.toPath());
            }
            String resized = thumbnail(source, cover);
            byte[] bytes;
            try { bytes = fetchImage(resized); }
            catch (IOException thumbnailFailure) {
                if (resized.equals(source)) throw thumbnailFailure;
                // Some CDN paths cannot be resized. Retry that exact original artwork once.
                bytes = fetchImage(source);
            }
            Files.createDirectories(directory.toPath());
            File temporary = File.createTempFile("image-", ".tmp", directory);
            try {
                Files.write(temporary.toPath(), bytes);
                Files.move(temporary.toPath(), cached.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary.toPath()); }
            trimCache();
            return bytes;
        }
    }

    private byte[] fetchImage(String url) throws IOException {
        byte[] bytes = downloader.fetch(url);
        if (bytes.length > MAX_IMAGE_BYTES || mime(bytes) == null) throw new IOException("Response is not a supported image");
        return bytes;
    }

    static boolean allowed(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String host = uri.getHost();
            if (host == null) return false;
            if (host.equals(EPIC_CHINA_IMAGE_HOST)) return true;
            for (String domain : new String[]{"epicgames.com", "unrealengine.com", "fab.com", "sketchfab.com"})
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            return false;
        } catch (IllegalArgumentException error) { return false; }
    }

    static String thumbnail(String source, boolean cover) {
        URI uri = URI.create(source);
        if (!"cdn1.epicgames.com".equals(uri.getHost()) || uri.getRawQuery() != null) return source;
        String query = cover ? "?resize=1&w=720&h=405&quality=medium" : "?resize=1&w=960&quality=medium";
        int fragment = source.indexOf('#');
        return (fragment < 0 ? source : source.substring(0, fragment)) + query;
    }

    static byte[] download(String source) throws IOException {
        return download(source, url -> (HttpURLConnection) URI.create(url).toURL().openConnection());
    }

    static byte[] download(String source, ConnectionOpener connections) throws IOException {
        String current = source;
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!allowed(current)) throw new IOException("图片地址或跳转目标不属于受支持的官方 HTTPS 图片节点");
            if (!visited.add(current)) throw new IOException("图片服务器发生循环跳转");
            HttpURLConnection connection = connections.open(current);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "image/webp,image/png,image/jpeg,image/gif");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects == MAX_REDIRECTS) throw new IOException("图片服务器跳转次数过多");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IOException("图片服务器跳转地址缺失");
                    try { current = URI.create(current).resolve(location).toString(); }
                    catch (IllegalArgumentException error) { throw new IOException("图片服务器跳转地址无效", error); }
                    continue;
                }
                if (status != 200) throw new IOException("图片请求失败 HTTP " + status);
                if (connection.getContentLengthLong() > MAX_IMAGE_BYTES) throw new IOException("图片超过大小限制");
                try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > MAX_IMAGE_BYTES) throw new IOException("图片超过大小限制");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("图片服务器跳转次数过多");
    }

    static String mime(byte[] bytes) {
        if (bytes.length < 12) return null;
        if ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "image/jpeg";
        if (Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if (new String(bytes, 0, 6, StandardCharsets.US_ASCII).matches("GIF8[79]a")) return "image/gif";
        if (new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        return null;
    }

    private synchronized void trimCache() throws IOException {
        File[] files = directory.listFiles(file -> file.getName().matches("[a-f0-9]{64}"));
        if (files == null) throw new IOException("Image cache cannot be read");
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= MAX_CACHE_BYTES) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
