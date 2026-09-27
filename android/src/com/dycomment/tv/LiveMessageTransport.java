package com.dycomment.tv;

import java.io.InputStream;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Official live HTML bootstrap + cursor-based read-only protobuf fetch, without account probes. */
final class LiveMessageTransport {
    private static final String ORIGIN = "https://live.douyin.com";
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    private String identity = "", cursor = "", internal = "";

    LiveMessageTransport(String session) {
        for (String part : session.split(";")) {
            int at = part.indexOf('=');
            if (at > 0) cookies.put(part.substring(0, at).trim(), part.substring(at + 1).trim());
        }
    }

    static String identity(String html) throws Exception {
        // Accept plain JSON and quote-escaped JSON embedded in the server hydration script.
        Matcher match = Pattern.compile("\"user_unique_id\\\\*\"\\s*:\\s*\\\\*\"([0-9]{5,25})")
                .matcher(html);
        if (!match.find()) throw new Exception("直播身份未就绪");
        return match.group(1);
    }

    static final class Batch {
        final List<LiveChatController.Chat> messages;
        final long interval;
        Batch(List<LiveChatController.Chat> messages, long interval) {
            this.messages = messages;
            this.interval = interval;
        }
    }

    Batch next(String room, int width, int height) throws Exception {
        if (identity.isEmpty()) identity = identity(new String(read("/", false), "UTF-8"));
        Map<String, String> query = SocialApi.params(
                "resp_content_type", "protobuf", "did_rule", "3", "app_name", "douyin_web",
                "endpoint", "live_pc", "support_wrds", "1", "identity", "audience",
                "need_persist_msg_count", "15", "version_code", "180800", "last_rtt", "0",
                "live_id", "1", "aid", "6383", "fetch_rule", "1", "device_platform", "web",
                "cookie_enabled", "true", "screen_width", String.valueOf(width),
                "screen_height", String.valueOf(height), "browser_language", "zh-CN",
                "browser_platform", "Win32", "browser_name", "Mozilla",
                "browser_version", SocialApi.UA.replaceFirst("^Mozilla/", ""),
                "browser_online", "true", "tz_name", TimeZone.getDefault().getID(),
                "room_id", room, "user_unique_id", identity);
        if (!cursor.isEmpty()) query.put("cursor", cursor);
        if (!internal.isEmpty()) query.put("internal_ext", internal);
        return decode(new Wire(read("/webcast/im/fetch/?" + SocialApi.encode(query), true)));
    }

    Batch decode(Wire response) throws Exception {
        if (response.bytes(2).length == 0) throw new Exception("直播消息响应无游标");
        cursor = response.text(2);
        internal = response.text(5);
        return new Batch(LiveChatController.chats(response),
                Math.max(1000, Math.min(30000, response.number(3, 2500))));
    }

    private byte[] read(String path, boolean protobuf) throws Exception {
        if (closed) throw new InterruptedException();
        HttpURLConnection request = (HttpURLConnection) new URL(ORIGIN + path).openConnection();
        connection = request;
        try {
            if (closed) throw new InterruptedException();
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(5000);
            request.setReadTimeout(5000);
            request.setRequestProperty("User-Agent", SocialApi.UA);
            request.setRequestProperty("Referer", ORIGIN + "/");
            request.setRequestProperty("Accept-Encoding", "identity");
            StringBuilder cookie = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (cookie.length() > 0) cookie.append("; ");
                cookie.append(entry.getKey()).append('=').append(entry.getValue());
            }
            request.setRequestProperty("Cookie", cookie.toString());
            if (request.getResponseCode() != 200) throw new Exception("直播消息暂不可用");
            String type = request.getContentType();
            if (protobuf && (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("proto")))
                throw new Exception("直播消息响应异常");
            for (Map.Entry<String, List<String>> header : request.getHeaderFields().entrySet()) {
                if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
                for (String value : header.getValue()) for (HttpCookie next : HttpCookie.parse(value)) {
                    String domain = next.getDomain();
                    if (domain != null && !domain.equalsIgnoreCase(".douyin.com")
                            && !domain.equalsIgnoreCase("douyin.com")
                            && !domain.equalsIgnoreCase("live.douyin.com")
                            && !domain.equalsIgnoreCase(".live.douyin.com")) continue;
                    if (next.getValue().length() > 16384 || cookies.size() > 100) continue;
                    if (next.getMaxAge() == 0) cookies.remove(next.getName());
                    else cookies.put(next.getName(), next.getValue());
                }
            }
            try (InputStream input = request.getInputStream()) { return QuickShareApi.read(input); }
        } finally {
            request.disconnect();
            if (connection == request) connection = null;
        }
    }

    void close() {
        closed = true;
        HttpURLConnection request = connection;
        if (request != null) request.disconnect();
    }
}
