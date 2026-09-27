package com.dycomment.tv;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Bounds the retained upstream client's reads without changing its query or nullable-result ABI.
 */
public final class LegacyRequests {
    public static JSONObject get(String path, Map<String, String> values, boolean web) {
        String session = SocialApi.cookie();
        long observed = CredentialHealth.observation(session);
        HttpURLConnection connection = null;
        try {
            if (path == null || !path.startsWith("/") || path.startsWith("//")) return null;
            Map<String, String> query =
                    values == null ? Collections.<String, String>emptyMap() : values;
            connection =
                    (HttpURLConnection)
                            new URL("https://www.douyin.com" + path + "?" + SocialApi.encode(query))
                                    .openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            String ua = SocialApi.UA;
            try {
                java.lang.reflect.Field f =
                        Class.forName("com.dycomment.tv.DouyinApi")
                                .getDeclaredField(web ? "webUserAgent" : "userAgent");
                f.setAccessible(true);
                ua = (String) f.get(null);
            } catch (Exception ignored) {
            }
            connection.setRequestProperty("User-Agent", ua);
            connection.setRequestProperty("Referer", "https://www.douyin.com/");
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (!session.isEmpty()) connection.setRequestProperty("Cookie", session);
            int status = connection.getResponseCode();
            if (status != 200) {
                CredentialHealth.observe(path, session, observed, status, null);
                return null;
            }
            try (InputStream in = connection.getInputStream();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                long until = android.os.SystemClock.elapsedRealtime() + 15000;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 16 * 1024 * 1024
                            || android.os.SystemClock.elapsedRealtime() > until
                            || Thread.currentThread().isInterrupted()) return null;
                    bytes.write(buffer, 0, n);
                }
                JSONObject result;
                try {
                    result = new JSONObject(bytes.toString("UTF-8"));
                } catch (Exception e) {
                    return null;
                }
                CredentialHealth.observe(path, session, observed, status, result);
                return result;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
