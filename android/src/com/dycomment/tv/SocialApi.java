package com.dycomment.tv;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Account operations use the Cookie imported on the television, never build-time credentials. */
public final class SocialApi {
    private static final ThreadPoolExecutor WORK =
            new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8));

    static {
        WORK.allowCoreThreadTimeOut(true);
    }

    static boolean submit(Runnable operation) {
        try {
            WORK.execute(operation);
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

    static String cookie() {
        try {
            return (String)
                    Class.forName("com.dycomment.tv.DouyinApi").getMethod("getCookie").invoke(null);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean personalCookie() {
        try {
            return !cookie().isEmpty()
                    && !((Boolean)
                            Class.forName("com.dycomment.tv.DouyinApi")
                                    .getMethod("isDefaultCookie")
                                    .invoke(null));
        } catch (Exception e) {
            return false;
        }
    }

    static Map<String, String> params(String... pairs) {
        Map<String, String> p = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) p.put(pairs[i], pairs[i + 1]);
        return p;
    }

    static String encode(Map<String, String> p) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return b.toString();
    }

    static JSONObject request(String path, Map<String, String> values, boolean post, String session)
            throws Exception {
        return requestAt("https://www.douyin.com", path, values, post, session);
    }

    static JSONObject requestAt(
            String origin, String path, Map<String, String> values, boolean post, String session)
            throws Exception {
        return requestAt(origin, path, values, post, session, true);
    }

    private static final class AccountRejected extends Exception { }

    static void validateAccountAt(String origin, String session) throws Exception {
        OfficialLoginPolicy.verified(requestAt(origin, OfficialLoginPolicy.SELF_PATH, params(), false, session, false));
    }

    static int probeAccount(String session) {
        return probeAccountAt("https://www.douyin.com", session);
    }

    static int probeAccountAt(String origin, String session) {
        try { validateAccountAt(origin, session); return CredentialHealth.AUTHENTICATED; }
        catch (AccountRejected rejected) { return CredentialHealth.UNAUTHENTICATED; }
        catch (Exception inconclusive) { return CredentialHealth.UNKNOWN; }
    }

    private static JSONObject requestAt(
            String origin, String path, Map<String, String> values, boolean post, String session, boolean observeHealth)
            throws Exception {
        if (session.isEmpty()) throw new Exception("请先从电脑同步账号");
        long observed = observeHealth ? CredentialHealth.observation(session) : -1;
        Map<String, String> common =
                params(
                        "device_platform",
                        "webapp",
                        "aid",
                        "6383",
                        "channel",
                        "channel_pc_web",
                        "version_code",
                        "170400",
                        "version_name",
                        "17.4.0",
                        "cookie_enabled",
                        "true");
        if (!post) common.putAll(values);
        HttpURLConnection c =
                (HttpURLConnection) new URL(origin + path + "?" + encode(common)).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(observeHealth ? 12000 : 5000);
        c.setReadTimeout(observeHealth ? 18000 : 5000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", origin + "/");
        c.setRequestProperty("Cookie", session);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (post) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Origin", "https://www.douyin.com");
                c.setRequestProperty(
                        "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                for (String part : session.split(";")) {
                    String s = part.trim();
                    if (s.startsWith("passport_csrf_token="))
                        c.setRequestProperty(
                                "X-Secsdk-Csrf-Token", s.substring(s.indexOf('=') + 1));
                }
                byte[] body = encode(values).getBytes("UTF-8");
                c.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int http = c.getResponseCode();
            if (observeHealth) CredentialHealth.observe(path, session, observed, http, null);
            if (!observeHealth && CredentialHealth.evidence(path, http, null) == CredentialHealth.UNAUTHENTICATED)
                throw new AccountRejected();
            if (http != 200) throw new Exception("接口暂不可用（HTTP " + http + "）");
            JSONObject result;
            try (InputStream in = c.getInputStream()) {
                if (path.equals("/webcast/web/feed/follow/")) result = readLiveResponse(in);
                else {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int n;
                    long deadline = android.os.SystemClock.elapsedRealtime() + (observeHealth ? 20000 : 5000);
                    while ((n = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()
                                || android.os.SystemClock.elapsedRealtime() > deadline)
                            throw new java.io.IOException("读取超时");
                        if (out.size() + n > (observeHealth ? 4 * 1024 * 1024 : 65536)) throw new Exception("响应过大，请稍后重试");
                        out.write(buffer, 0, n);
                    }
                    try {
                        result = new JSONObject(out.toString("UTF-8"));
                    } catch (Exception e) {
                        throw new Exception("接口未返回有效数据，请稍后重试");
                    }
                }
            }
            if (observeHealth) CredentialHealth.observe(path, session, observed, http, result);
            if (!observeHealth && CredentialHealth.evidence(path, http, result) == CredentialHealth.UNAUTHENTICATED)
                throw new AccountRejected();
            if (!result.has("status_code") || result.optInt("status_code", -1) != 0) {
                throw new Exception("接口暂未完成请求，请稍后重试");
            }
            if (path.equals("/aweme/v1/web/comment/list/") && !result.has("comments")) {
                throw new Exception("评论接口没有返回列表");
            }
            if (OfficialLoginPolicy.SELF_PATH.equals(path)) OfficialLoginPolicy.verified(result);
            return result;
        } finally {
            c.disconnect();
        }
    }

    // Followed-live responses can contain megabytes of unrelated room metadata.
    // Stream and discard it rather than retaining the response plus a full JSON tree on old TVs.
    static JSONObject readLiveResponse(InputStream in) throws Exception {
        InputStream bounded =
                new java.io.FilterInputStream(in) {
                    int count;
                    final long deadline = android.os.SystemClock.elapsedRealtime() + 20000;

                    void add(int n) throws java.io.IOException {
                        if (Thread.currentThread().isInterrupted()
                                || android.os.SystemClock.elapsedRealtime() > deadline)
                            throw new java.io.IOException("读取超时");
                        if (n > 0 && (count += n) > 16 * 1024 * 1024)
                            throw new java.io.IOException("直播响应过大");
                    }

                    @Override
                    public int read() throws java.io.IOException {
                        int n = in.read();
                        if (n != -1) add(1);
                        return n;
                    }

                    @Override
                    public int read(byte[] b, int o, int n) throws java.io.IOException {
                        int size = in.read(b, o, n);
                        add(size);
                        return size;
                    }
                };
        try (JsonReader reader = new JsonReader(new InputStreamReader(bounded, "UTF-8"))) {
            Object result = compactLive(reader, 0);
            if (!(result instanceof JSONObject)) throw new Exception("直播数据格式异常");
            return (JSONObject) result;
        }
    }

    static final Set<String> LIVE_FIELDS =
            new HashSet<>(
                    java.util.Arrays.asList(
                            "data",
                            "room",
                            "is_recommend",
                            "id_str",
                            "title",
                            "owner",
                            "nickname",
                            "sec_uid",
                            "follow_info",
                            "follow_status",
                            "stream_url",
                            "hls_pull_url_map",
                            "flv_pull_url",
                            "hls_pull_url",
                            "SD1",
                            "HD1",
                            "SD2",
                            "FULL_HD1",
                            "status_code",
                            "cover",
                            "avatar_thumb",
                            "url_list"));

    static Object compactLive(JsonReader r, int depth) throws Exception {
        if (depth > 12) throw new Exception("直播数据层级异常");
        JsonToken token = r.peek();
        if (token == JsonToken.BEGIN_OBJECT) {
            JSONObject o = new JSONObject();
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (LIVE_FIELDS.contains(name)) o.put(name, compactLive(r, depth + 1));
                else r.skipValue();
            }
            r.endObject();
            return o;
        }
        if (token == JsonToken.BEGIN_ARRAY) {
            JSONArray a = new JSONArray();
            r.beginArray();
            while (r.hasNext()) {
                if (a.length() >= 2000) throw new Exception("直播列表过大");
                a.put(compactLive(r, depth + 1));
            }
            r.endArray();
            return a;
        }
        if (token == JsonToken.NULL) {
            r.nextNull();
            return JSONObject.NULL;
        }
        if (token == JsonToken.BOOLEAN) return r.nextBoolean();
        return r.nextString();
    }

    static final class State {
        int liked = -1, collected = -1, followed = -1;
        int recommended = -1, recommendAllowed = -1;
        String uid = "";
    }

    static State state(String id, String secUid, String session) throws Exception {
        JSONObject r =
                request("/aweme/v1/web/aweme/detail/", params("aweme_id", id), false, session);
        JSONObject item = r.optJSONObject("aweme_detail");
        if (item == null) throw new Exception("视频状态暂不可用");
        State s = new State();
        s.liked = item.optInt("user_digged", -1);
        s.collected = item.optInt("collect_stat", item.optInt("collect_status", -1));
        if (item.opt("user_recommend_status") instanceof Number)
            s.recommended = item.optInt("user_recommend_status", -1) == 2 ? 1 : 0;
        JSONObject permissions = item.optJSONObject("status");
        if (permissions != null && permissions.opt("allow_self_recommend_to_friend") instanceof Boolean)
            s.recommendAllowed = permissions.optBoolean("allow_self_recommend_to_friend") ? 1 : 0;
        JSONObject author = item.optJSONObject("author");
        if (author != null) {
            s.followed = author.optInt("follow_status", -1);
            s.uid = author.optString("uid", "");
        }
        if (s.followed < 0 && !secUid.isEmpty()) {
            JSONObject p =
                    request(
                                    "/aweme/v1/web/user/profile/other/",
                                    params("sec_user_id", secUid),
                                    false,
                                    session)
                            .optJSONObject("user");
            if (p != null) {
                s.followed = p.optInt("follow_status", -1);
                s.uid = p.optString("uid", s.uid);
            }
        }
        return s;
    }

    static State authorState(String secUid, String session) throws Exception {
        JSONObject user =
                request(
                                "/aweme/v1/web/user/profile/other/",
                                params("sec_user_id", secUid),
                                false,
                                session)
                        .optJSONObject("user");
        if (user == null) throw new Exception("作者状态暂不可用");
        State state = new State();
        state.followed = user.optInt("follow_status", -1);
        state.uid = user.optString("uid", "");
        return state;
    }

    /** Notification badges only. Does not fetch messages or mark anything read. */
    static int unread(String session) throws Exception {
        return parseUnread(request("/aweme/v1/web/notice/count/", params(), false, session));
    }

    static int parseUnread(JSONObject r) throws Exception {
        JSONArray counts = r.optJSONArray("notice_count");
        if (counts == null) throw new Exception("通知计数暂不可用");
        Set<Integer> groups = new HashSet<>();
        long total = 0;
        for (int i = 0; i < counts.length(); i++) {
            JSONObject n = counts.optJSONObject(i);
            if (n != null && groups.add(n.optInt("group", -1)))
                total += Math.max(0, n.optInt("count", 0));
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    static final class Live {
        String id, title, author, secUid, stream, preview;
    }

    static List<Live> followedLive(String session) throws Exception {
        JSONObject self =
                request("/aweme/v1/web/user/profile/self/", params(), false, session)
                        .optJSONObject("user");
        if (self == null || self.optString("uid").isEmpty())
            throw new Exception("登录已失效，请从电脑更新账号");
        return parseLive(
                request(
                        "/webcast/web/feed/follow/",
                        params(
                                "scene",
                                "aweme_pc_follow_top",
                                "update_version_code",
                                "170400",
                                "pc_client_type",
                                "1",
                                "count",
                                "20",
                                "cursor",
                                "0"),
                        false,
                        session));
    }

    static List<Live> parseLive(JSONObject r) throws Exception {
        JSONObject data = r.optJSONObject("data");
        JSONArray entries = data == null ? null : data.optJSONArray("data");
        if (entries == null) throw new Exception("关注直播数据暂不可用，请稍后重试");
        List<Live> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || entry.optInt("is_recommend", 0) != 0) continue;
            JSONObject room = entry.optJSONObject("room");
            if (room == null) continue;
            JSONObject owner = room.optJSONObject("owner");
            if (owner == null) continue;
            JSONObject follow = owner.optJSONObject("follow_info");
            if (follow != null
                    && follow.has("follow_status")
                    && follow.optInt("follow_status") == 0) continue;
            Live live = new Live();
            live.id = room.optString("id_str", "");
            if (live.id.isEmpty() || !seen.add(live.id)) continue;
            live.title = room.optString("title", "直播中");
            live.author = owner.optString("nickname", "主播");
            live.secUid = owner.optString("sec_uid", "");
            live.stream = stream(room.optJSONObject("stream_url"));
            live.preview = imageUrl(room.optJSONObject("cover"));
            if (live.preview.isEmpty())
                live.preview = imageUrl(owner.optJSONObject("avatar_thumb"));
            result.add(live);
        }
        return result;
    }

    static String imageUrl(JSONObject image) {
        JSONArray urls = image == null ? null : image.optJSONArray("url_list");
        if (urls != null)
            for (int i = 0; i < urls.length(); i++) {
                String url = urls.optString(i, "");
                if (url.startsWith("https://")) return url;
            }
        return "";
    }

    static String stream(JSONObject stream) {
        if (stream == null) return "";
        for (String kind : new String[] {"hls_pull_url_map", "flv_pull_url"}) {
            JSONObject map = stream.optJSONObject(kind);
            if (map != null)
                for (String quality : new String[] {"SD1", "HD1", "SD2", "FULL_HD1"}) {
                    String url = map.optString(quality, "");
                    if (url.startsWith("https://") || url.startsWith("http://")) return url;
                }
        }
        return stream.optString("hls_pull_url", "");
    }
}
