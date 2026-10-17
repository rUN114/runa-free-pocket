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
import java.util.concurrent.ConcurrentHashMap;

/** Public artwork only: no account cookies, tokens, or arbitrary remote URLs. */
final class OfficialImages {
    static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 80L * 1024 * 1024;
    private final File directory;
    interface Downloader { byte[] fetch(String url) throws IOException; }
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
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Image cache unavailable");
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
        if (!allowed(source)) throw new IOException("Unofficial image address");
        HttpURLConnection connection = (HttpURLConnection) URI.create(source).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "image/webp,image/png,image/jpeg,image/gif");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Image HTTP " + connection.getResponseCode());
            if (connection.getContentLengthLong() > MAX_IMAGE_BYTES) throw new IOException("Image too large");
            try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_IMAGE_BYTES) throw new IOException("Image too large");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
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
