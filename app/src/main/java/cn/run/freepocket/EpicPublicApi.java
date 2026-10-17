package cn.run.freepocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class EpicPublicApi {
    private EpicPublicApi() {}

    static String promotions(String country) throws IOException {
        // The promotion feed is public. Native HTTP avoids depending on storefront CORS policy.
        URL url = new URL("https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions"
                + "?locale=en-US&country=" + country + "&allowCountries=" + country);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Epic 活动接口 HTTP " + status);
            try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("活动接口响应超出预期大小");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }
}
