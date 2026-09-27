package com.dycomment.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/** Friend lookup and explicitly selected browser-backed shares. */
final class QuickShareApi {
    static final String UNAVAILABLE = "好友分享暂不可用";

    static final class UnavailableException extends Exception {
        UnavailableException() {
            super(UNAVAILABLE);
        }
    }

    /**
     * A readable friend list or a healthy web Cookie does not establish an IM send session.
     * The current official web sender uses its live passport/identity-security SDK context;
     * the paired browser must explicitly advertise a verified share capability.
     */
    static void requireShareCapability() throws UnavailableException {
        if (!BrowserActionBroker.available("share", SocialApi.cookie())) throw new UnavailableException();
    }

    static final class Friend {
        String uid, name, avatar;
    }

    static final class Page {
        final List<Friend> friends = new ArrayList<>();
        String cursor;
        boolean more;
    }

    static Page friends(String cookie, String cursor) throws Exception {
        JSONObject r =
                SocialApi.requestAt(
                        "https://imdesktop.douyin.com",
                        "/aweme/v1/web/familiar/list/",
                        SocialApi.params(
                                "aid",
                                "339757",
                                "cursor",
                                cursor,
                                "count",
                                "30",
                                "need_all_friend",
                                "1",
                                "version_code",
                                "21.6.0"),
                        false,
                        cookie);
        return parseFriends(r);
    }

    static Page parseFriends(JSONObject r) throws Exception {
        JSONArray users = r.optJSONArray("user_list");
        if (users == null) throw new Exception("好友列表暂不可用");
        Page p = new Page();
        p.cursor = r.optString("cursor", "0");
        p.more = r.optBoolean("has_more", r.optInt("has_more", 0) == 1);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.optJSONObject(i);
            if (u == null || u.optBoolean("user_canceled", false)) continue;
            Friend f = new Friend();
            f.uid = u.optString("uid", "");
            if (!f.uid.matches("[0-9]+") || !seen.add(f.uid)) continue;
            f.name = u.optString("remark_name", "");
            if (f.name.isEmpty()) f.name = u.optString("nickname", "好友");
            f.avatar = SocialApi.imageUrl(u.optJSONObject("avatar_thumb"));
            p.friends.add(f);
        }
        return p;
    }

    /** Bounded binary reader also used by the read-only live transport. */
    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long end = android.os.SystemClock.elapsedRealtime() + 15000;
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > 2 * 1024 * 1024
                    || android.os.SystemClock.elapsedRealtime() > end
                    || Thread.currentThread().isInterrupted()) throw new IOException("读取超时或过大");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    static String share(Context context, String id, String friend, String cookie) throws Exception {
        requireShareCapability();
        if (!id.matches("[0-9]+") || !friend.matches("[0-9]+")) throw new Exception("视频或好友信息无效");
        JSONObject result = BrowserActionBroker.perform(context, "share",
                new JSONObject().put("video_id", id).put("friend_id", friend), cookie);
        if (!Boolean.TRUE.equals(result.opt("confirmed"))) throw new Exception("分享结果尚未确认");
        return "已发送给好友";
    }
}
